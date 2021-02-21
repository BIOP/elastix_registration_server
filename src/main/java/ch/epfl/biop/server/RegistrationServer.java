/*-
 * #%L
 * BIOP Elastix Registration Server
 * %%
 * Copyright (C) 2021 Nicolas Chiaruttini, EPFL
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the EPFL, ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP), 2021 nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package ch.epfl.biop.server;

import ch.epfl.biop.wrappers.elastix.Elastix;
import ch.epfl.biop.wrappers.transformix.Transformix;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.scijava.util.VersionUtils;

import javax.servlet.MultipartConfigElement;
import java.io.File;

public class RegistrationServer {

    RegistrationServerConfig config;

    public RegistrationServer(RegistrationServerConfig config) {

        this.config = config;

        System.out.println("--- Initialisation of registration server version "+ VersionUtils.getVersion(RegistrationServer.class));

        System.out.println("--- Setting elastix location (warning : global settings) : " + config.elaxtixLocation);
        Elastix.setExePath(new File(config.elaxtixLocation));

        System.out.println("--- Setting transformix location (warning : global settings) : " + config.transformixLocation);
        Transformix.setExePath(new File(config.transformixLocation));

        System.out.println("--- Settings initial Job indexes [elastix:" + config.initialElastixJobIndex + "; transformix:" + config.initialTransformixIndex + "]");
        ElastixJobQueueServlet.jobIndex = config.initialElastixJobIndex;
        TransformixServlet.jobIndex = config.initialTransformixIndex;

        System.out.println("--- Settings servlet request timeout (ms) " + config.requestTimeOutInMs);
        ElastixServlet.timeOut = config.requestTimeOutInMs;
        TransformixServlet.timeOut = config.requestTimeOutInMs;


        System.out.println("--- Settings elastix servlet max number of simultaneous requests " + config.maxNumberOfSimultaneousRequests);
        ElastixServlet.maxNumberOfSimultaneousRequests = config.maxNumberOfSimultaneousRequests;
        //TransformixServlet.maxNumberOfSimultaneousRequests = config.maxNumberOfSimultaneousRequests;

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

    final public static String STATUS_PATH = "/";
    final public static String ELASTIX_PATH = "/elastix";
    final public static String ELASTIX_QUEUE_PATH = "/elastix/startjob";
    final public static String TRANSFORMIX_PATH = "/transformix";

    final public static int DefaultLocalPort = 8090;

    public void start(int localPort) throws Exception {

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setAttribute("javax.servlet.context.tempdir",new File("tmp"));

        int maxThreads = Math.max(4,4+config.maxNumberOfSimultaneousRequests);
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
        shElastix.getRegistration().setMultipartConfig(new MultipartConfigElement("", config.maxFileSize, 2 * config.maxFileSize, 20*1024*1024));

        ServletHolder shTransformix = context.addServlet(TransformixServlet.class, TRANSFORMIX_PATH);
        //shTransformix.setAsyncSupported(true);
        shTransformix.getRegistration().setMultipartConfig(new MultipartConfigElement("", config.maxFileSize, 2 * config.maxFileSize, 20*1024*1024));

        StatusServlet.setConfiguration(config);
        context.addServlet(StatusServlet.class, STATUS_PATH);

        ElastixJobQueueServlet.setConfiguration(config);
        context.addServlet(ElastixJobQueueServlet.class, ELASTIX_QUEUE_PATH);

        server.start();
    }

    void stop() throws Exception {
        server.stop();
    }
}
