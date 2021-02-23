package ch.epfl.biop.server;

import com.google.gson.Gson;
import org.scijava.util.VersionUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Status servlet: easily called by
 *
 * http://servername/
 *
 * Returned a json file representing the server status:
 *  - its configuration
 *  - how many jobs have processed, how many are processed, how many are on the queue
 *
 *  see {@link StatusServlet.ServerStatus} for all info being sent
 *
 */

public class StatusServlet extends HttpServlet {

    public static void setConfiguration(RegistrationServerConfig config) {
        StatusServlet.config = config;
    }

    static RegistrationServerConfig config;

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().println(new Gson().toJson(new ServerStatus()));
    }

    public static class ServerStatus {

        String serverVersion = VersionUtils.getVersion(RegistrationServer.class); // Returns the version declared in the pom file

        long currentElastixJobIndex = ElastixJobQueueServlet.jobIndex;

        long currentTransformixJobIndex = TransformixServlet.jobIndex;

        int numberOfCurrentElastixTasks = ElastixServlet.getNumberOfCurrentTasks();

        int numberOfCurrentTransformixTasks = TransformixServlet.getNumberOfCurrentTasks();

        int numberOfElastixTasksEnqueued = ElastixJobQueueServlet.getQueueSize();

        int estimatedQueueProcessingTimeInS = ElastixJobQueueServlet.getQueueSize()*StatusServlet.config.elastixTaskEstimatedDurationInMs/1000;

        int numberOfRejectedRequestsBecauseOfFullQueue = ElastixJobQueueServlet.numberOfRejectedRequestsFullQueue.get();

        RegistrationServerConfig config = StatusServlet.config;
    }
}
