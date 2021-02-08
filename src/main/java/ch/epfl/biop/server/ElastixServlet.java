package ch.epfl.biop.server;

import ch.epfl.biop.wrappers.elastix.DefaultElastixTask;
import ch.epfl.biop.wrappers.elastix.ElastixTask;
import ch.epfl.biop.wrappers.elastix.ElastixTaskSettings;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipOutputStream;

import static ch.epfl.biop.server.ServletUtils.copyFileToServer;
import static ch.epfl.biop.utils.ZipDirectory.zipFile;

public class ElastixServlet extends HttpServlet{

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

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().println("{ \"status\": \"ok\"}");
    }

    public static long jobIndex=0;

    static synchronized long getJobIndex() {
        jobIndex++;
        return jobIndex;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {

        final long currentJobId = getJobIndex();

        final AtomicBoolean isAlive = new AtomicBoolean(true);

        Runnable taskToPerform = () -> {
            try {
                System.out.println("----------- ELASTIX JOB " + currentJobId + " START");
                ElastixTaskSettings settings = new ElastixTaskSettings();
                settings.singleThread();

                String fImagePath = copyFileToServer(elastixJobsFolder, request, FixedImageTag, "fixed_" + currentJobId);
                settings.fixedImage(() -> fImagePath);
                String mImagePath = copyFileToServer(elastixJobsFolder, request, MovingImageTag, "moving_" + currentJobId);
                settings.movingImage(() -> mImagePath);

                // Is there an initial transform file ?
                Part iniTransformPart = request.getPart(InitialTransformTag);

                if (iniTransformPart != null) {
                    String iniTransformPath = copyFileToServer(elastixJobsFolder, request, InitialTransformTag, "iniTransform_" + currentJobId);
                    settings.addInitialTransform(iniTransformPath);
                }

                Part numberOfTransformsPart = request.getPart(NumberOfTransformsTag);
                String strNTransforms = IOUtils.toString(numberOfTransformsPart.getInputStream(), StandardCharsets.UTF_8.name());
                Integer numberOfTransforms = new Integer(strNTransforms);

                for (int idxTransform = 0; idxTransform < numberOfTransforms; idxTransform++) {
                    String transformPath = copyFileToServer(elastixJobsFolder, request, TransformParameterTag(idxTransform), "transform_" + currentJobId + "_" + idxTransform);
                    settings.addTransform(() -> transformPath);
                }

                if (!new File(elastixJobsFolder, "job_" + currentJobId).exists()) {
                    Files.createDirectory(Paths.get(elastixJobsFolder, "job_" + currentJobId));
                }
                String outputFolder = elastixJobsFolder + "job_" + currentJobId;
                settings.outFolder(() -> outputFolder);

                ElastixTask elastixTask = new DefaultElastixTask();
                elastixTask.setSettings(settings);

                if (isAlive.get()) {
                    try {
                        elastixTask.run();
                        if (isAlive.get()) {
                            String sourceFile = outputFolder;
                            FileOutputStream fos = new FileOutputStream(elastixJobsFolder + "res_" + currentJobId + ".zip");
                            ZipOutputStream zipOut = new ZipOutputStream(fos);
                            File fileToZip = new File(sourceFile);

                            zipFile(fileToZip, fileToZip.getName(), zipOut);
                            zipOut.close();
                            fos.close();

                            File fileResZip = new File(elastixJobsFolder + "res_" + currentJobId + ".zip");

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
                        } else {
                            System.out.println("Job "+currentJobId+" interrupted");
                        }

                    } catch (Exception e) {
                        System.out.println("Error during elastix request");
                        response.setStatus(Response.SC_INTERNAL_SERVER_ERROR);
                    }
                } else {
                    System.out.println("Job "+currentJobId+" interrupted");
                }
            } catch (IOException|ServletException  e) {
                response.setStatus(Response.SC_INTERNAL_SERVER_ERROR);
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(taskToPerform);

        try {
            future.get(timeOut, TimeUnit.MILLISECONDS);
            System.out.println("Completed successfully");
        } catch (InterruptedException e) {
            isAlive.set(false);
        } catch (ExecutionException e) {
            isAlive.set(false);
        } catch (TimeoutException e) {
            System.out.println("Timed out. Cancelling the runnable...");
            isAlive.set(false);
            future.cancel(true);
        }
        executor.shutdown();
    }

}