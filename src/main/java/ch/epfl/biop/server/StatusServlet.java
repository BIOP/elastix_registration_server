package ch.epfl.biop.server;

import com.google.gson.Gson;
import org.scijava.util.VersionUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

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
        String serverVersion = VersionUtils.getVersion(RegistrationServer.class);
        long currentElastixJobIndex = ElastixJobQueueServlet.jobIndex;
        long currentTransformixJobIndex = TransformixServlet.jobIndex;
        int numberOfCurrentElastixTasks = ElastixServlet.getNumberOfCurrentTasks();
        int numberOfCurrentTransformixTasks = TransformixServlet.getNumberOfCurrentTasks();
        int numberOfElastixTasksEnqueued = ElastixJobQueueServlet.getQueueSize();
        int estimatedQueueProcessingTimeInS = ElastixJobQueueServlet.getQueueSize()*StatusServlet.config.elastixTaskEstimatedDurationInMs/1000;
        RegistrationServerConfig config = StatusServlet.config;
    }
}
