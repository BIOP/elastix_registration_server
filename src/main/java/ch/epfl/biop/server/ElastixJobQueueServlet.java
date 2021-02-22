package ch.epfl.biop.server;

import com.google.gson.Gson;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * This servlet handles a queue for all the elastix registration job. This is necessary
 * because a registration task can easily take a few seconds and many jobs can be required in the same
 * time window.
 *
 * Each client has to go through this queue before sending the
 * real registration request that will be processed by the {@link ElastixServlet}
 *
 * Here's how the queue work:
 *
 * A client wants to perform a new registration.
 * He first performs a POST request to ask whether he can start its registration job to this {@link ElastixJobQueueServlet} servlet:
 *
 * http://servername/elastix/startjob?id=-1
 *
 * because the id provided in the POST request is -1, the server thus understands that it's a first request
 * the registration job is new and thus is not yet part of the queue. In this case, the server:
 *  - returns to the client:
 *      - a unique id for this future job: ze_id,
 *      - and an estimated waiting time in ms before the client can start its registration
 *          - if this estimated waiting is greater than {@link RegistrationServerConfig#maxDelayBetweenQueueUpdateRequestInS}
 *          then the max delay is returned
 *  - and creates and puts the a new {@link WaitingJob} referencing this task in the queue
 *
 * The client receives the response.
 *      If the (estimated) waiting time returned is 0, the task can be performed:
 *          The client performs a POST request directly the the {@link ElastixServlet} servlet :
 *          http://servername/elastix?id=ze_id which be sent to the {@link ElastixServlet}
 *
 *      If the waiting time is not 0, the client should wait the time specified before sending a new request:
 *          http://servername/elastix/startjob?id=ze_id
 *          with its id in the POST content.
 *          The server returns the new waiting time which should - at some point go down to 0
 *          finally reaching the point where the client can launch its registration
 *
 * Two events can occur which can break this mechanism:
 *      - the client may 'forget' to ask again when to start the job, leaving the {@link WaitingJob}
 *      forever in the queue. This can happen either because the connection was lost
 *      or because the registration task was cancelled.
 *           If that's the case, then a cleaner thread removes all jobs which have been forgotten for more than
 *           a certain amount of time {@link ElastixJobQueueServlet#cleanupTimeoutInS}
 *
 *      - the number of tasks requested becomes too big, the estimated time to process the queue
 *      then exceeds {@link RegistrationServerConfig#maxQueueEstimatedWaitingTimeInS}. In this case,
 *      the client received a 503 error code.
 */

public class ElastixJobQueueServlet extends HttpServlet {

    public static Consumer<String> log = (str) -> {};//System.out.println(ElastixJobQueueServlet.class+":"+str);

    /**
     * Queue containing the job that are expected to be processed in the future
     * this object also served as the main synchronization lock to avoid concurrency issues
     */
    final static LinkedList<WaitingJob> queue = new LinkedList<>();

    /**
     * Queue containing the jobs that are ready to be processed (waiting time sent to the client = 0)
     *
     * This queue will be emptied by the {@link ElastixServlet} when the client
     * ask to perform the registration
     */
    final static ArrayList<WaitingJob> queueReadyToBeProcessed = new ArrayList<>();

    /**
     * If the client forget to ask for the queue state update for more than this value
     * the linked {@link WaitingJob} is removed from the queue
     */
    public static int cleanupTimeoutInS = 5;

    /**
     * Can be configured in {@link RegistrationServerConfig}, max estimated queue size in second
     * before the client requests are ignored and given a 503 error code
     */
    public static int maxWaitingQueueTimeInS = 120; // 2 min max waiting time, after the jobs are not processed

    /**
     * Can be configured in {@link RegistrationServerConfig}
     * Value taken to estimate the average time that takes an elastix registration job
     */
    public static int estimatedElastixJobProcessingTimeInMs = 3000;

    /**
     * Can be configured in {@link RegistrationServerConfig}
     * see there for details
     */
    public static int maxDelayBetweenQueueUpdateRequestInS = 10;

    /**
     * Cleaner thread - checks every {@link ElastixJobQueueServlet#cleanupTimeoutInS} whether
     * a task has not been forgotten ba the client
     *
     * Cleans both {@link ElastixJobQueueServlet#queue} and {@link ElastixJobQueueServlet#queueReadyToBeProcessed}
     */
    public static Thread wall_e;

    static {
        // statically launch the cleaner thread
        wall_e = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(cleanupTimeoutInS * 1000); // it makes sense to wait the cleanup time before each check
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                synchronized (queue) { // locks on the queue

                    LocalDateTime now = LocalDateTime.now();

                    List<WaitingJob> jobsToRemove = queue.stream()
                         .filter(job -> {
                             if (job.updateTimeTarget!=null) { // If this field is not initialized, it's an early job
                                 //log.accept("Clean check : "+job.updateTimeTarget);
                                 //log.accept("Job should be updated before "+job.updateTimeTarget.plusSeconds(cleanupTimeoutInS));
                                 //log.accept("And it is "+now);
                                 return job.updateTimeTarget.plusSeconds(cleanupTimeoutInS).isBefore(now);
                             } else return false;
                         }).collect(Collectors.toList());

                    queue.removeAll(jobsToRemove);

                    if (jobsToRemove.size()>0) {
                        log.accept("Number of jobs removed because of timeout : "+jobsToRemove.size());
                    }

                    synchronized (queueReadyToBeProcessed) { // TODO : is it the right lock ?
                        try {
                            Thread.sleep(cleanupTimeoutInS * 1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        jobsToRemove = queueReadyToBeProcessed.stream()
                                .filter(job -> {
                                    if (job.updateTimeTarget!=null) {
                                    return job.updateTimeTarget.plusSeconds(cleanupTimeoutInS).isAfter(now);
                                    } else return false;
                                })
                                .collect(Collectors.toList());

                        queueReadyToBeProcessed.removeAll(jobsToRemove);

                        if (jobsToRemove.size()>0) {
                            log.accept("(Ready) number of jobs removed because of timeout : "+jobsToRemove.size());
                        }

                    }
                }

            }
        });
        wall_e.start();
    }

    /**
     * Appends configuration to this servlet
     * @param config provided configuration
     */
    public static void setConfiguration(RegistrationServerConfig config) {
        maxWaitingQueueTimeInS = config.maxQueueEstimatedWaitingTimeInS;
        estimatedElastixJobProcessingTimeInMs = config.elastixTaskEstimatedDurationInMs;
        maxDelayBetweenQueueUpdateRequestInS = config.maxDelayBetweenQueueUpdateRequestInS;
    }

    /**
     * @return the number of jobs contained in the queue
     */
    public static int getQueueSize() {
        synchronized (queue) {
            return queue.size();
        }
    }

    // Get method not supported
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    static long jobIndex=0;

    static synchronized long getNextJobIndex() {
        jobIndex++;
        return jobIndex;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Is it a new job ( = id = -1  ? ) or an old job asking for an update ?

        synchronized (queue) { // only one request processed at a time - this should be fine because it's fast - also avoids cleaning of the queue while processing the request

            long requestId = Long.parseLong(request.getParameter("id"));

            // First : create or retrieve the referenced waiting job
            WaitingJob wjob;

            if (requestId == -1) {
                // New job
                log.accept("New job to enqueue:"+requestId);
                wjob = new WaitingJob();
                wjob.jobId = getNextJobIndex();
                queue.add(wjob);
            } else {
                log.accept("Already existing job :"+requestId);
                // Already existing job
                // Let's try to get it, if it has not been cleaned
                Optional<WaitingJob> j = queue.stream().filter(job -> job.jobId == requestId).findFirst();
                if (j.isPresent()) {
                    wjob = j.get();
                } else {
                    log.accept("Invalid request : job not found, maybe it does not exists or it has been cleaned, or it has already been set as ready to be processed");
                    response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
                    return; // end of request
                }
            }

            // Ok now let's estimate the time needed before the request can be started

            int numberOfTasksWaiting =
                    ElastixServlet.getNumberOfCurrentTasks()         // Number of tasks being effectively processed
                    - ElastixServlet.maxNumberOfSimultaneousRequests // subtract the max number of task which can be processed
                    + queueReadyToBeProcessed.size()                 // number of tasks queued (ready)
                    + queue.indexOf(wjob)                            // number of tasks queued (not ready)
                    + 1;

            if (numberOfTasksWaiting<=0) {
                // We can actually process the task immediately
                // Move job from waiting queue to ready queue
                queue.remove(wjob);
                queueReadyToBeProcessed.add(wjob);
                // Let's warn the client he can start : waiting time = 0
                wjob.waitingTimeInMs = 0;
                wjob.updateTimeTarget = LocalDateTime.now();
            } else {
                // Too many jobs waiting - the server cannot process the job immediately

                // With the formula below, the job in front of the queue is pretty active:
                // it sends a request every 5 per cent of the estimated task duration
                int waitingTimeInMs = (int) ((numberOfTasksWaiting-0.95)*estimatedElastixJobProcessingTimeInMs);

                wjob.waitingTimeInMs = waitingTimeInMs;

                // If the estimated woiting is above the threshold : 503 error code sent to the client
                if (wjob.waitingTimeInMs/1000>maxWaitingQueueTimeInS) {
                    log.accept("Too many elastix job requests in elastix queue servlet - expected time exceed "+maxWaitingQueueTimeInS+" seconds");
                    queue.remove(wjob);
                    response.setStatus(503); // Too many requests - server temporarily unavailable
                    return;
                }

                // We don't want the client to wait too long before asking for a queue state update
                // see maxDelayBetweenQueueUpdateRequestInS in RegistrationConfigClass for an explanation
                waitingTimeInMs = Math.min(waitingTimeInMs, maxDelayBetweenQueueUpdateRequestInS*1000);

                log.accept("Update update time");
                wjob.updateTimeTarget = LocalDateTime.now().plusSeconds((waitingTimeInMs/1000)+1);

                log.accept("Updated update time to "+wjob.updateTimeTarget);
            }

            response.setContentType("application/json");
            // Send jsonized version of WaitingJob class
            response.getWriter().println(new Gson().toJson(wjob));
            response.setStatus(HttpServletResponse.SC_OK);
        }

    }

    /**
     * Inner class representing a job waiting to be processed
     */
    public static class WaitingJob {

        /**
         * id given by the server on first request
         */
        public long jobId;

        /**
         * Estimated waiting time that the job will spend in the queue
         */
        public int waitingTimeInMs;

        /**
         * Kept in the server : it's the estimated date when the client
         * should ask for a queue update state.
         *
         * This is used by {@link ElastixJobQueueServlet#wall_e} in order to know
         * when a job has been forgotten by the client and should be cleaned
         */
        volatile transient LocalDateTime updateTimeTarget;
    }
}
