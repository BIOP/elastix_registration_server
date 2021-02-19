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

import ch.epfl.biop.wrappers.transformix.DefaultTransformixTask;
import ch.epfl.biop.wrappers.transformix.TransformixTask;
import ch.epfl.biop.wrappers.transformix.TransformixTaskSettings;
import org.eclipse.jetty.server.Response;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipOutputStream;

import static ch.epfl.biop.server.ServletUtils.copyFileToServer;
import static ch.epfl.biop.utils.ZipDirectory.zipFile;

public class TransformixServlet extends HttpServlet {

    final public static String InputPtsFileTag = "InputPts";
    final public static String TransformFilesTag = "transformFiles";
    public static String transformixJobsFolder = "src/test/resources/tmp/transformix/";

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().println("{ \"status\": \"ok\"}");
    }

    public static long jobIndex=0;
    public static int timeOut = 50000;

    public static void setJobsDataLocation(String jobsDataLocation) throws IOException {
        if (jobsDataLocation.endsWith(File.separator)) {
            transformixJobsFolder = jobsDataLocation + "transformix" + File.separator;
        } else {
            transformixJobsFolder = jobsDataLocation + File.separator + "transformix" + File.separator;
        }

        File joblocation = new File(transformixJobsFolder);
        if (!joblocation.exists()) {
            Files.createDirectory(Paths.get(transformixJobsFolder));
        }
    }

    static synchronized long getJobIndex() {
        jobIndex++;
        return jobIndex;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {

        final long currentJobId = getJobIndex();

        AsyncContext async = request.startAsync();
        async.setTimeout(timeOut);

        new Thread(() -> {
            try {
                System.out.println("Job " + currentJobId + " started");
                System.out.println("----------- STARTING TRANSFORMIX JOB " + currentJobId);
                numberOfCurrentTask.getAndIncrement();

                TransformixTaskSettings settings = new TransformixTaskSettings();

                String ptsPath = copyFileToServer(transformixJobsFolder, request, InputPtsFileTag, "pts_" + currentJobId);
                settings.pts(() -> ptsPath);
                String mImagePath = copyFileToServer(transformixJobsFolder, request, TransformFilesTag, "transforms_" + currentJobId);
                settings.transform(() -> mImagePath);

                if (!new File(transformixJobsFolder, "job_" + currentJobId).exists()) {
                    Files.createDirectory(Paths.get(transformixJobsFolder, "job_" + currentJobId));
                }
                String outputFolder = transformixJobsFolder + "job_" + currentJobId;
                settings.outFolder(() -> outputFolder);

                TransformixTask transformixTask = new DefaultTransformixTask();
                transformixTask.setSettings(settings);

                try {
                    transformixTask.run();
                    String sourceFile = outputFolder;
                    FileOutputStream fos = new FileOutputStream(transformixJobsFolder + "res_" + currentJobId + ".zip");
                    ZipOutputStream zipOut = new ZipOutputStream(fos);
                    File fileToZip = new File(sourceFile);

                    zipFile(fileToZip, fileToZip.getName(), zipOut);
                    zipOut.close();
                    fos.close();

                    File fileResZip = new File (transformixJobsFolder + "res_" + currentJobId + ".zip");

                    String registrationResultFileName = "transformix_result.zip";

                    response.setContentType("application/zip");
                    response.addHeader("Content-Disposition", "attachment; filename=" + registrationResultFileName);
                    response.setContentLength((int) fileResZip.length());

                    FileInputStream fileInputStream = new FileInputStream(fileResZip);
                    ServletOutputStream responseOutputStream = response.getOutputStream();
                    int bytes;
                    while ((bytes = fileInputStream.read()) != -1) {
                        responseOutputStream.write(bytes);
                    }

                    System.out.println("----------- ENDING JOB " + currentJobId);
                    responseOutputStream.close();
                    fileInputStream.close();
                    response.setStatus(Response.SC_OK);
                    async.complete();
                    numberOfCurrentTask.decrementAndGet();

                } catch (Exception e) {
                    e.printStackTrace();
                    response.setStatus(Response.SC_INTERNAL_SERVER_ERROR);
                    async.complete();
                    numberOfCurrentTask.decrementAndGet();
                }


            } catch (IOException|ServletException e) {
                e.printStackTrace();
                response.setStatus(Response.SC_INTERNAL_SERVER_ERROR);
                async.complete();
                numberOfCurrentTask.decrementAndGet();
            }
        }).start();

    }

    static AtomicInteger numberOfCurrentTask = new AtomicInteger(0);

    public static int getNumberOfCurrentTasks() {
        return numberOfCurrentTask.get();
    }
}
