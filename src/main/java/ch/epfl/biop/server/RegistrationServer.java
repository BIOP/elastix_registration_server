package ch.epfl.biop.server;

import ch.epfl.biop.wrappers.elastix.Elastix;
import ch.epfl.biop.wrappers.transformix.Transformix;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import javax.servlet.MultipartConfigElement;
import java.io.File;

public class RegistrationServer {

    RegistrationServerConfig config;

    public RegistrationServer(RegistrationServerConfig config) {

        this.config = config;

        System.out.println("--- Setting elastix location (warning : global settings) : " + config.elaxtixLocation);
        Elastix.setExePath(new File(config.elaxtixLocation));

        System.out.println("--- Setting transformix location (warning : global settings) : " + config.transformixLocation);
        Transformix.setExePath(new File(config.transformixLocation));

        System.out.println("--- Settings initial Job indexes [elastix:" + config.initialElastixJobIndex + "; transformix:" + config.initialTransformixIndex + "]");
        ElastixServlet.jobIndex = config.initialElastixJobIndex;
        TransformixServlet.jobIndex = config.initialTransformixIndex;

        System.out.println("--- Settings servlet request timeout (ms) " + config.requestTimeOutInMs);
        ElastixServlet.timeOut = config.requestTimeOutInMs;
        TransformixServlet.timeOut = config.requestTimeOutInMs;


        try {
            System.out.print("--- Settings jobs data location for elastix : ");
            ElastixServlet.setJobsDataLocation(config.jobsDataLocation);
            System.out.println(ElastixServlet.elastixJobsFolder);

            System.out.print("--- Settings jobs data location for transformix : ");
            TransformixServlet.setJobsDataLocation(config.jobsDataLocation);
            System.out.println(TransformixServlet.transformixJobsFolder);
        } catch (Exception e) {
            System.err.println("Error during server creation:");
            e.printStackTrace();
        }
    }

    private Server server;

    final public static String ELASTIX_PATH = "/elastix";
    final public static String TRANSFORMIX_PATH = "/transformix";

    final public static int DefaultLocalPort = 8090;

    public void start(int localPort) throws Exception {

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setAttribute("javax.servlet.context.tempdir",new File("tmp"));

        int maxThreads = Math.max(4,2+config.maxNumberOfSimultaneousRequests);
        int minThreads = 1;
        int idleTimeout = 120;

        QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, minThreads, idleTimeout);

        server = new Server(threadPool);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(localPort);
        server.setConnectors(new Connector[] { connector });
        server.setHandler(context);

        ServletHolder shElastix = context.addServlet(ElastixServlet.class, ELASTIX_PATH);
        //shElastix.setAsyncSupported(true);
        shElastix.getRegistration().setMultipartConfig(new MultipartConfigElement("", 1024*1024, 2*1024*1024, 20*1024*1024));

        ServletHolder shTransformix = context.addServlet(TransformixServlet.class, TRANSFORMIX_PATH);
        //shTransformix.setAsyncSupported(true);
        shTransformix.getRegistration().setMultipartConfig(new MultipartConfigElement("", 1024*1024, 2*1024*1024, 20*1024*1024));

        server.start();
    }

    void stop() throws Exception {
        server.stop();
    }
}
