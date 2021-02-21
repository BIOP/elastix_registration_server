package ch.epfl.biop.server;

import com.google.gson.Gson;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
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

    final static LinkedList<WaitingJob> queue = new LinkedList();

    final static ArrayList<WaitingJob> queueReadyToBeProcessed = new ArrayList();

    public static int cleanupTimeoutInS = 5;

    public static int maxWaitingQueueTimeInS = 120; // 2 min max waiting time, after the jobs are not processed

    public static Thread cleaner;

    static {
        cleaner = new Thread(() -> {
            while (true) {
                synchronized (queue) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    LocalDateTime now = LocalDateTime.now();

                    List<WaitingJob> jobsToRemove = queue.stream()
                         .filter(job -> job.updateTimeTarget.plusSeconds(cleanupTimeoutInS).isAfter(now))
                            .collect(Collectors.toList());

                    if (jobsToRemove.size()>0) {
                        System.out.println("Jobs removed because of timeout : "+jobsToRemove.size());
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
                            .filter(job -> job.updateTimeTarget.plusSeconds(cleanupTimeoutInS).isAfter(now))
                            .collect(Collectors.toList());

                    if (jobsToRemove.size()>0) {
                        System.out.println("(Ready) jobs removed because of timeout : "+jobsToRemove.size());
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

        long requestId = Long.valueOf(request.getParameter("id"));

        WaitingJob wjob;

        if (requestId == -1) {
            // New job
            final long currentJobId = getJobIndex();
            wjob = new WaitingJob();
            wjob.jobId = getJobIndex();
            queue.add(wjob);
        } else {
            // Already existing job
            // Let's try to get it, if it has not been cleaned
            synchronized (queue) {
                Optional<WaitingJob> j = queue.stream().filter(job -> job.jobId == requestId).findFirst();
                if (j.isPresent()) {
                    wjob = j.get();
                } else {
                    System.err.println("Invalid request : job not found, maybe it does not exists or it has been cleaned, or it has already been set as ready to be processed");
                    response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
                    return;
                }
            }
        }

        // Ok now let's estimate the time needed before the request can be started

        synchronized (queue) {
            int numberOfTasksWaiting = ElastixServlet.getNumberOfCurrentTasks() - ElastixServlet.maxNumberOfSimultaneousRequests
                    + queueReadyToBeProcessed.size() + queue.indexOf(wjob);

            if (numberOfTasksWaiting<=0) {
                queue.remove(wjob);
                queueReadyToBeProcessed.add(wjob);
                wjob.waitingTimeInMs = 0;
                wjob.updateTimeTarget = LocalDateTime.now();
            } else {
                int waitingTimeInMs = (int) ((numberOfTasksWaiting-0.99)*3000);
                wjob.waitingTimeInMs = waitingTimeInMs;

                if (wjob.waitingTimeInMs/1000>maxWaitingQueueTimeInS) {
                    System.err.println("Too many elastix job requests in elastix queue servlet - expected time exceed "+maxWaitingQueueTimeInS+" seconds");
                    response.setStatus(503); // Too many requests - server temporarily unavailable
                    return;
                }

                wjob.updateTimeTarget = LocalDateTime.now().plusSeconds((waitingTimeInMs/1000)+1);
            }
        }

        response.setContentType("application/json");
        response.getWriter().println(new Gson().toJson(wjob));
        response.setStatus(HttpServletResponse.SC_OK);

    }

    public static class WaitingJob {
        long jobId;
        int waitingTimeInMs;
        transient LocalDateTime updateTimeTarget;
    }
}
