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

import ch.epfl.biop.wrappers.elastix.DefaultElastixTask;
import ch.epfl.biop.wrappers.elastix.ElastixTask;
import ch.epfl.biop.wrappers.elastix.ElastixTaskSettings;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Response;

import javax.servlet.*;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipOutputStream;

import static ch.epfl.biop.server.ServletUtils.copyFileToServer;
import static ch.epfl.biop.utils.ZipDirectory.zipFile;

public class ElastixServlet extends HttpServlet{

    public static Consumer<String> log = (str) -> {};//System.out.println(ElastixServlet.class+":"+str);

    public static int maxNumberOfSimultaneousRequests = 1;

    final public static String TaskMetadata = "taskMetadata";
    final public static String FixedImageTag = "fixedImage";
    final public static String MovingImageTag = "movingImage";
    final public static String InitialTransformTag = "initialTransform";
    final public static String NumberOfTransformsTag = "numberOfTransforms";

    public static String elastixJobsFolder = "src/test/resources/tmp/elastix/";
    public static int timeOut = 50000;

    final static public String TransformParameterTag(int index) {
        return "transformParam_"+index;
    }

    public static void setJobsDataLocation(String jobsDataLocation) throws IOException {
        if (jobsDataLocation.endsWith(File.separator)) {
            elastixJobsFolder = jobsDataLocation + "elastix" + File.separator;
        } else {
            elastixJobsFolder = jobsDataLocation + File.separator + "elastix" + File.separator;
        }

        File joblocation = new File(elastixJobsFolder);
        if (!joblocation.exists()) {
            Files.createDirectory(Paths.get(elastixJobsFolder));
        }
    }

    static AtomicInteger numberOfCurrentTask = new AtomicInteger(0);

    public static int getNumberOfCurrentTasks() {
        return numberOfCurrentTask.get();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {

        final AtomicBoolean isAlive = new AtomicBoolean(true);

        Runnable taskToPerform = () -> {
            try {

                if (request.getParameter("id")==null) {
                    log.accept("Registration job has no id - this request will not be processed");
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }

                int currentJobId = Integer.valueOf(request.getParameter("id"));

                synchronized (ElastixJobQueueServlet.queue) {
                    Optional<ElastixJobQueueServlet.WaitingJob> job = ElastixJobQueueServlet.queueReadyToBeProcessed.stream()
                            .filter(j -> j.jobId == currentJobId).findFirst();
                    if (job.isPresent()) {
                        ElastixJobQueueServlet.queueReadyToBeProcessed.remove(job.get());
                    } else {
                        log.accept("Job "+currentJobId+" has not been been queued before - this request will not be processed");
                        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        return;
                    }
                }

                synchronized (ElastixServlet.class) {
                    if (numberOfCurrentTask.get()<maxNumberOfSimultaneousRequests) {
                        numberOfCurrentTask.getAndIncrement();
                    } else {
                        log.accept("Too many elastix requests in elastix servlet");
                        response.setStatus(503); // Too many requests - server temporarily unavailable - this should not happen with the queueing system however ...
                        return;
                    }
                }

                log.accept("----------- ELASTIX JOB " + currentJobId + " START");

                ElastixTaskSettings settings = new ElastixTaskSettings();
                settings.singleThread();

                // --- Task Info
                //request.getPart(TaskMetadata).getInputStream();

                if (!new File(elastixJobsFolder, "job_" + currentJobId).exists()) {
                    Files.createDirectory(Paths.get(elastixJobsFolder, "job_" + currentJobId));
                }

                String currentElastixJobFolder = Paths.get(elastixJobsFolder, "job_" + currentJobId).toString()+File.separator;

                String currentElastixJobFolderInputs = currentElastixJobFolder+"input"+File.separator;

                String currentElastixJobFolderOutputs = currentElastixJobFolder+"output"+File.separator;

                if (!new File(currentElastixJobFolderInputs).exists()) {
                    Files.createDirectory(Paths.get(elastixJobsFolder, "job_" + currentJobId, "input"));
                }

                if (!new File(currentElastixJobFolderOutputs).exists()) {
                    Files.createDirectory(Paths.get(elastixJobsFolder, "job_" + currentJobId, "output"));
                }

                String fImagePath = copyFileToServer(currentElastixJobFolderInputs, request, FixedImageTag, "fixed_" + currentJobId);
                settings.fixedImage(() -> fImagePath);

                String mImagePath = copyFileToServer(currentElastixJobFolderInputs, request, MovingImageTag, "moving_" + currentJobId);
                settings.movingImage(() -> mImagePath);

                // Is there an initial transform file ?
                Part iniTransformPart = request.getPart(InitialTransformTag);

                if (iniTransformPart != null) {
                    String iniTransformPath = copyFileToServer(currentElastixJobFolderInputs, request, InitialTransformTag, "iniTransform_" + currentJobId);
                    settings.addInitialTransform(iniTransformPath);
                }

                Part numberOfTransformsPart = request.getPart(NumberOfTransformsTag);
                String strNTransforms = IOUtils.toString(numberOfTransformsPart.getInputStream(), StandardCharsets.UTF_8.name());
                Integer numberOfTransforms = new Integer(strNTransforms);

                for (int idxTransform = 0; idxTransform < numberOfTransforms; idxTransform++) {
                    String transformPath = copyFileToServer(currentElastixJobFolderInputs, request, TransformParameterTag(idxTransform), "transform_" + currentJobId + "_" + idxTransform);
                    settings.addTransform(() -> transformPath);
                }

                String outputFolder = currentElastixJobFolderOutputs;//elastixJobsFolder + "job_" + currentJobId;
                settings.outFolder(() -> outputFolder);

                ElastixTask elastixTask = new DefaultElastixTask();
                elastixTask.setSettings(settings);

                if (isAlive.get()) {
                    try {
                        elastixTask.run();
                        if (isAlive.get()) {
                            String sourceFile = outputFolder;

                            cleanLogFiles(outputFolder);

                            FileOutputStream fos = new FileOutputStream(currentElastixJobFolder + "res_" + currentJobId + ".zip");
                            ZipOutputStream zipOut = new ZipOutputStream(fos);
                            File fileToZip = new File(sourceFile);

                            zipFile(fileToZip, fileToZip.getName(), zipOut);
                            zipOut.close();
                            fos.close();

                            File fileResZip = new File(currentElastixJobFolder + "res_" + currentJobId + ".zip");

                            String registrationResultFileName = "registration_result.zip";

                            FileInputStream fileInputStream = new FileInputStream(fileResZip);
                            ServletOutputStream responseOutputStream = response.getOutputStream();
                            int bytes;
                            while ((bytes = fileInputStream.read()) != -1) {
                                responseOutputStream.write(bytes);
                            }
                            responseOutputStream.close();
                            fileInputStream.close();

                            response.setContentType("application/zip");
                            response.addHeader("Content-Disposition", "attachment; filename=" + registrationResultFileName);
                            response.setContentLength((int) fileResZip.length());
                            response.setStatus(Response.SC_OK);

                            // Clean Up : let's remove the output folder because it has already been zipped

                            eraseFolder(outputFolder);

                            if (!StatusServlet.config.storeJobsData) {
                                // Server set to not store anything -> just delete the data
                                eraseFolder(currentElastixJobFolder);
                            } else {
                                // Server can store some user data, if the user agrees
                            }

                        } else {
                            log.accept("Job "+currentJobId+" interrupted");
                            eraseFolder(currentElastixJobFolder);
                        }
                        numberOfCurrentTask.decrementAndGet();

                    } catch (Exception e) {
                        numberOfCurrentTask.decrementAndGet();
                        log.accept("Error during elastix request");
                        response.setStatus(Response.SC_INTERNAL_SERVER_ERROR);
                        e.printStackTrace();
                        eraseFolder(currentElastixJobFolder);
                    }
                } else {
                    log.accept("Job "+currentJobId+" interrupted");
                    numberOfCurrentTask.decrementAndGet();
                    eraseFolder(currentElastixJobFolder);
                }
            } catch (IOException|ServletException  e) {
                response.setStatus(Response.SC_INTERNAL_SERVER_ERROR);
                numberOfCurrentTask.decrementAndGet();
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(taskToPerform);

        try {
            future.get(timeOut, TimeUnit.MILLISECONDS);
            log.accept("Completed successfully");
        } catch (InterruptedException e) {
            isAlive.set(false);
        } catch (ExecutionException e) {
            isAlive.set(false);
        } catch (TimeoutException e) {
            log.accept("Timed out. Cancelling the runnable...");
            isAlive.set(false);
            future.cancel(true);
        }
        executor.shutdown();
    }

    private void cleanLogFiles(String outputFolder) {
        File[] allContents = new File(outputFolder).listFiles();
        for (File f : allContents) {
            if (f.getName().startsWith("elastix")||f.getName().startsWith("IterationInfo")) {
                f.delete();
            }
        }
    }

    private static void eraseFolder(String currentElastixJobFolder) {
        try {
            FileUtils.deleteDirectory(new File(currentElastixJobFolder));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
