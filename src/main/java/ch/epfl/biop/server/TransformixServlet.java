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

                } catch (Exception e) {
                    e.printStackTrace();
                    response.setStatus(Response.SC_INTERNAL_SERVER_ERROR);
                    async.complete();
                }


            } catch (IOException|ServletException e) {
                e.printStackTrace();
                response.setStatus(Response.SC_INTERNAL_SERVER_ERROR);
                async.complete();
            }
        }).start();

    }
}
