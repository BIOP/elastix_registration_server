package ch.epfl.biop.server;

import com.google.gson.Gson;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The client wants to perform a registration.
 *
 * He performs a POST request to ask whether he can start its registration job:
 *
 * http://servername/elastix/startjob
 *
 * If there the id provided in the POST request is -1,
 * the server returns a unique id for the future job,
 * and a waiting time in ms before the client can start its registration
 * and puts it in the linked list
 *
 * If the waiting time is 0, the task can be performed :
 * POST request directly on elastix : http://servername/elastix
 *
 * If the waiting time is not 0, the client should wait the time specified before sending a new request:
 * http://servername/elastix/startjob
 * with its id in the POST content.
 *
 * The server returns the new waiting time before the client can launch its registration
 *
 * Several problems can occur, especially the client may 'forget' to ask again when to start the job
 *
 * If that's the case, then a cleaner thread removes all jobs which have been forgotten for more than
 * a certain amount of time (cleanupTimeoutInMs)
 *
 *
 *
 *
 */

public class ElastixJobQueueServlet extends HttpServlet {

    public static Consumer<String> log = (str) -> {};//System.out.println(ElastixServlet.class+":"+str);

    final static LinkedList<WaitingJob> queue = new LinkedList();

    final static ArrayList<WaitingJob> queueReadyToBeProcessed = new ArrayList();

    public static int cleanupTimeoutInS = 5;

    public static int maxWaitingQueueTimeInS = 120; // 2 min max waiting time, after the jobs are not processed

    public static Thread cleaner;

    static {
        cleaner = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                synchronized (queue) {

                    LocalDateTime now = LocalDateTime.now();

                    List<WaitingJob> jobsToRemove = queue.stream()
                         .filter(job -> {
                             if (job.updateTimeTarget!=null) {
                                 log.accept("Clean check : "+job.updateTimeTarget);
                                 log.accept("Job should be updated before "+job.updateTimeTarget.plusSeconds(cleanupTimeoutInS));
                                 log.accept("And it is "+now);
                                 return job.updateTimeTarget.plusSeconds(cleanupTimeoutInS).isBefore(now);
                             } else return false;
                         })
                            .collect(Collectors.toList());

                    if (jobsToRemove.size()>0) {
                        log.accept("Number of jobs removed because of timeout : "+jobsToRemove.size());
                    }

                    queue.removeAll(jobsToRemove);
                }

                synchronized (queueReadyToBeProcessed) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    LocalDateTime now = LocalDateTime.now();

                    List<WaitingJob> jobsToRemove = queueReadyToBeProcessed.stream()
                            .filter(job -> {
                                if (job.updateTimeTarget!=null) {
                                return job.updateTimeTarget.plusSeconds(cleanupTimeoutInS).isAfter(now);
                                } else return false;
                            })
                            .collect(Collectors.toList());

                    if (jobsToRemove.size()>0) {
                        log.accept("(Ready) number jobs removed because of timeout : "+jobsToRemove.size());
                    }

                    queueReadyToBeProcessed.removeAll(jobsToRemove);
                }

            }
        });
        cleaner.start();
    }

    public static void setConfiguration(RegistrationServerConfig config) {
        // TODO
    }

    // Get method not supported
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    public static long jobIndex=0;

    static synchronized long getJobIndex() {
        jobIndex++;
        return jobIndex;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Is it a new job ( = id = -1  ? ) or an old job asking for an update ?

        synchronized (queue) {

            long requestId = Long.valueOf(request.getParameter("id"));

            WaitingJob wjob;

            if (requestId == -1) {
                // New job
                log.accept("New job to enqueue:"+requestId);
                wjob = new WaitingJob();
                wjob.jobId = getJobIndex();
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
                        return;
                    }
            }

            // Ok now let's estimate the time needed before the request can be started

            int numberOfTasksWaiting = ElastixServlet.getNumberOfCurrentTasks() - ElastixServlet.maxNumberOfSimultaneousRequests
                    + queueReadyToBeProcessed.size() + queue.indexOf(wjob) + 1;

            if (numberOfTasksWaiting<=0) {
                queue.remove(wjob);
                queueReadyToBeProcessed.add(wjob);
                wjob.waitingTimeInMs = 0;
                wjob.updateTimeTarget = LocalDateTime.now();
            } else {
                int waitingTimeInMs = (int) ((numberOfTasksWaiting-0.99)*3000);
                wjob.waitingTimeInMs = waitingTimeInMs;

                if (wjob.waitingTimeInMs/1000>maxWaitingQueueTimeInS) {
                    log.accept("Too many elastix job requests in elastix queue servlet - expected time exceed "+maxWaitingQueueTimeInS+" seconds");
                    response.setStatus(503); // Too many requests - server temporarily unavailable
                    return;
                }
                log.accept("Update update time");
                wjob.updateTimeTarget = LocalDateTime.now().plusSeconds((waitingTimeInMs/1000)+1);

                log.accept("Updated update time to "+wjob.updateTimeTarget);
            }

            response.setContentType("application/json");
            response.getWriter().println(new Gson().toJson(wjob));
            response.setStatus(HttpServletResponse.SC_OK);
        }

    }

    public static class WaitingJob {
        public long jobId;
        public int waitingTimeInMs;
        volatile transient LocalDateTime updateTimeTarget;
    }
}
