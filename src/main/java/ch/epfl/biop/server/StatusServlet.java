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
