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
import java.nio.charset.Charset;
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

/**
 * Servlet which processes an Elastix task.
 *
 * The client has to go through the queueing process handled by {@link ElastixJobQueueServlet}
 * before becoming a valid request for {@link ElastixServlet}.
 *
 * The server checks thanks to its id whether the request is valid (is it in the {@link ElastixJobQueueServlet#queueReadyToBeProcessed} ?)
 *
 * The client then sends a MultiPart request which contains:
 * - the task metadata (optional) as text
 * - the fixed image (file)
 * - the moving image (file)
 * - the elastix transformation parameter file (text file)
 *
 * The server executes locally on the server this elastix task and returns the resulting transformation file
 *
 * If some metadata where sent, the input images, the metadata and the resulting transformation are
 * stored in the server as a zip file, provided that {@link RegistrationServerConfig#storeJobsData} is true
 *
 * Otherwise all data are deleted.
 *
 * Note : all jobs are performed on a single thread
 *
 */

public class ElastixServlet extends HttpServlet{

    public static Consumer<String> log = (str) -> {};//System.out.println(ElastixServlet.class+":"+str);

    /**
     * Can be configured in {@link RegistrationServerConfig}
     */
    public static int maxNumberOfSimultaneousRequests = 1;

    /**
     * Tags to identity multipart http request parts
     */
    final public static String TaskMetadata = "taskMetadata";
    final public static String FixedImageTag = "fixedImage";
    final public static String MovingImageTag = "movingImage";
    final public static String InitialTransformTag = "initialTransform";
    final public static String NumberOfTransformsTag = "numberOfTransforms";

    /**
     * Can be configured in {@link RegistrationServerConfig}
     */
    public static String elastixJobsFolder = "src/test/resources/tmp/elastix/";

    /**
     * Can be configured in {@link RegistrationServerConfig}, http timeout
     */
    public static int timeOut = 50000;

    /**
     * Several successive transformations can exist in an elastix registration job
     * this function serves to generate the tags for the multipart request retrieval
     * @param index of the (potentially) multiple transformation file
     * @return the tag to identify the http tag
     */
    static public String TransformParameterTag(int index) {
        return "transformParam_"+index;
    }

    /**
     * @param jobsDataLocation param given by the server config
     * @throws IOException if the temp folder cannot be created
     */
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

    /**
     * Atomic integer to keep track of the number of currently processed tasks
     */
    static AtomicInteger numberOfCurrentTask = new AtomicInteger(0);

    public static int getNumberOfCurrentTasks() {
        return numberOfCurrentTask.get();
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().println("{ \"status\": \"ok\"}");
    }

    /**
     * Where the elastix registration happens
     * @param request client
     * @param response of the server
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {

        // Flag which indicates whether the job has been / should be cancelled
        final AtomicBoolean isAlive = new AtomicBoolean(true);

        // Notify that we're processing a task
        numberOfCurrentTask.getAndIncrement();

        // Not sure whether it's useful to put it into a Runnable...
        Runnable taskToPerform = () -> {
            try {

                if (request.getParameter("id")==null) {
                    log.accept("Registration job has no id - this request will not be processed");
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    numberOfCurrentTask.decrementAndGet();
                    return;
                }

                int currentJobId = Integer.parseInt(request.getParameter("id"));

                synchronized (ElastixJobQueueServlet.queue) {
                    Optional<ElastixJobQueueServlet.WaitingJob> job = ElastixJobQueueServlet.queueReadyToBeProcessed.stream()
                            .filter(j -> j.jobId == currentJobId).findFirst();
                    if (job.isPresent()) {
                        // Ok - it's a valid job - let's remove it from the ready queue
                        ElastixJobQueueServlet.queueReadyToBeProcessed.remove(job.get());
                    } else {
                        log.accept("Job "+currentJobId+" has not been queued before - this request will not be processed");
                        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        numberOfCurrentTask.decrementAndGet();
                        return;
                    }
                }

                synchronized (ElastixServlet.class) {
                    if (numberOfCurrentTask.get()>maxNumberOfSimultaneousRequests) {
                        log.accept("Too many elastix requests in elastix servlet");
                        response.setStatus(503); // Too many requests - server temporarily unavailable - this should not happen with the queueing system however ...
                        numberOfCurrentTask.decrementAndGet();
                        return;
                    }
                }

                log.accept("----------- ELASTIX JOB " + currentJobId + " START");

                ElastixTaskSettings settings = new ElastixTaskSettings();
                settings.singleThread(); //

                String taskMetadata = null;

                // --- Task Info
                if (request.getPart(TaskMetadata)!=null) {
                    InputStream taskInfoStream = request.getPart(TaskMetadata).getInputStream();
                    taskMetadata = IOUtils.toString(taskInfoStream, StandardCharsets.UTF_8);
                }

                if (taskMetadata!=null) {
                    log.accept("Task Metadata = "+taskMetadata);
                } else {
                    log.accept("No task metadata");
                }

                // Prepare temp folders
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

                // Copy files to server HDD and sets elastix job settings
                String fImagePath = copyFileToServer(currentElastixJobFolderInputs, request, FixedImageTag, "fixed" );
                settings.fixedImage(() -> fImagePath);

                String mImagePath = copyFileToServer(currentElastixJobFolderInputs, request, MovingImageTag, "moving" );
                settings.movingImage(() -> mImagePath);

                // Is there an initial transform file ?
                Part iniTransformPart = request.getPart(InitialTransformTag);

                if (iniTransformPart != null) {
                    String iniTransformPath = copyFileToServer(currentElastixJobFolderInputs, request, InitialTransformTag, "iniTransform" );
                    settings.addInitialTransform(iniTransformPath);
                }

                // Retrieves the number of transforms in the request - get their number first
                Part numberOfTransformsPart = request.getPart(NumberOfTransformsTag);
                String strNTransforms = IOUtils.toString(numberOfTransformsPart.getInputStream(), StandardCharsets.UTF_8.name());
                int numberOfTransforms = new Integer(strNTransforms);

                // Gets all successive transforms and copy to server hdd
                for (int idxTransform = 0; idxTransform < numberOfTransforms; idxTransform++) {
                    String transformPath = copyFileToServer(currentElastixJobFolderInputs, request, TransformParameterTag(idxTransform), "transform_" + idxTransform);
                    settings.addTransform(() -> transformPath);
                }

                // Where to store the result
                String outputFolder = currentElastixJobFolderOutputs;//elastixJobsFolder + "job_" + currentJobId;
                settings.outFolder(() -> outputFolder);

                ElastixTask elastixTask = new DefaultElastixTask();
                elastixTask.setSettings(settings);

                if (isAlive.get()) { // not cancelled ?
                    try {

                        elastixTask.run(); // DOES the registration thus most of the time is spent there

                        if (isAlive.get()) { // still not cancelled ?

                            String sourceFile = outputFolder;

                            // Removes (unnecessary?) log files
                            cleanLogFiles(outputFolder);

                            // Zip response files to send back to the client
                            FileOutputStream fos = new FileOutputStream(currentElastixJobFolder + "res.zip");
                            ZipOutputStream zipOut = new ZipOutputStream(fos);
                            File fileToZip = new File(sourceFile);

                            ServletUtils.zipFile(fileToZip, fileToZip.getName(), zipOut);
                            zipOut.close();
                            fos.close();

                            File fileResZip = new File(currentElastixJobFolder + "res.zip");
                            String registrationResultFileName = "registration_result.zip";

                            // Really sends back the result
                            FileInputStream fileInputStream = new FileInputStream(fileResZip);
                            ServletOutputStream responseOutputStream = response.getOutputStream();
                            int bytes;
                            while ((bytes = fileInputStream.read()) != -1) {
                                responseOutputStream.write(bytes);
                            }
                            responseOutputStream.close();
                            fileInputStream.close();

                            // Response information
                            response.setContentType("application/zip");
                            response.addHeader("Content-Disposition", "attachment; filename=" + registrationResultFileName);
                            response.setContentLength((int) fileResZip.length());
                            response.setStatus(Response.SC_OK);

                            // Clean Up : let's remove the output folder because it has already been zipped
                            ServletUtils.eraseFolder(outputFolder);

                            // Should we store the job data ?
                            if (!StatusServlet.config.storeJobsData) {
                                // Server set to not store anything -> just delete the data
                                ServletUtils.eraseFolder(currentElastixJobFolder);
                            } else {
                                // Server can store some user data, if the user agrees
                                if (taskMetadata == null) {
                                    // No metadata = no user agreement to store job, erase data
                                    ServletUtils.eraseFolder(currentElastixJobFolder);
                                } else {
                                    // We have some metadata : the user agreed to store data
                                    FileUtils.writeStringToFile(new File(currentElastixJobFolderInputs,"metadata.txt"), taskMetadata, Charset.defaultCharset());

                                    // Zip result folder (factor 2 gained on average)
                                    fos = new FileOutputStream(elastixJobsFolder + "job_"+currentJobId+".zip");
                                    zipOut = new ZipOutputStream(fos);
                                    fileToZip = new File(currentElastixJobFolder);

                                    ServletUtils.zipFile(fileToZip, fileToZip.getName(), zipOut);
                                    zipOut.close();
                                    fos.close();

                                    // and delete original result folder
                                    ServletUtils.eraseFolder(currentElastixJobFolder);
                                }
                            }

                        } else {
                            log.accept("Job "+currentJobId+" interrupted");
                            ServletUtils.eraseFolder(currentElastixJobFolder);
                        }

                        // Don't forget to decrement that the number of current processed jobs
                        numberOfCurrentTask.decrementAndGet();

                    } catch (Exception e) {
                        numberOfCurrentTask.decrementAndGet();
                        log.accept("Error during elastix request");
                        response.setStatus(Response.SC_INTERNAL_SERVER_ERROR);
                        e.printStackTrace();
                        ServletUtils.eraseFolder(currentElastixJobFolder);
                    }
                } else {
                    log.accept("Job "+currentJobId+" interrupted");
                    numberOfCurrentTask.decrementAndGet();
                    ServletUtils.eraseFolder(currentElastixJobFolder);
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
        } catch (InterruptedException | ExecutionException e) {
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
        if (allContents!=null) {
            for (File f : allContents) {
                if (f.getName().startsWith("elastix") || f.getName().startsWith("IterationInfo")) {
                    f.delete();
                }
            }
        }
    }

}
