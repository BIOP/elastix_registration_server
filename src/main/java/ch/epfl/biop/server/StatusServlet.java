package ch.epfl.biop.server;

import com.google.gson.Gson;

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
        RegistrationServerConfig config = StatusServlet.config;
        int numberOfCurrentElastixTasks = ElastixServlet.getNumberOfCurrentTasks();
        int numberOfCurrentTransformixTasks = TransformixServlet.getNumberOfCurrentTasks();
    }
}
