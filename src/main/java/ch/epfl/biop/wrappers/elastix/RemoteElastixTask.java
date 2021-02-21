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
package ch.epfl.biop.wrappers.elastix;

import ch.epfl.biop.server.ElastixJobQueueServlet;
import ch.epfl.biop.server.ElastixServlet;
import com.google.gson.Gson;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static ch.epfl.biop.server.RegistrationServer.ELASTIX_PATH;
import static ch.epfl.biop.server.RegistrationServer.ELASTIX_QUEUE_PATH;

public class RemoteElastixTask extends ElastixTask {

    public static Consumer<String> log = (str) -> System.out.println(RemoteElastixTask.class+":"+str);

    String serverUrl;
    String serverUrlQueue;

    public RemoteElastixTask(String serverUrl) {
        this.serverUrl = serverUrl+ELASTIX_PATH;
        this.serverUrlQueue = serverUrl+ELASTIX_QUEUE_PATH;
    }

    String extraInfo = "";

    public RemoteElastixTask(String serverUrl, String extraInfo) {
        this.serverUrl = serverUrl+ELASTIX_PATH;
        this.serverUrlQueue = serverUrl+ELASTIX_QUEUE_PATH;
        this.extraInfo = extraInfo;
    }

    public void run() throws Exception {

        int timeoutMs = 50000;
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs).build();

        CloseableHttpClient httpclient =
                HttpClientBuilder
                        .create()
                        .setDefaultRequestConfig(config)
                        .setRetryHandler((exception, executionCount, context) -> {
                            if (executionCount > 3) {
                                log.accept("Maximum tries reached for client http pool ");
                                return false;
                            }
                            if (exception instanceof org.apache.http.NoHttpResponseException) {
                                log.accept("No response from server on " + executionCount + " call");
                                return true;
                            }
                            return false;
                        })
                        .build();

        // Queuing job
        long jobId = 1;

        HttpPost enqueueJobRequest = new HttpPost(serverUrlQueue+"?id=-1");

        HttpResponse response;
        try {
            response = httpclient.execute(enqueueJobRequest);
        } catch (ClientProtocolException e) {
            e.printStackTrace();
            throw new HttpException("Server queueing registration failed with error message : "+e.getMessage());
        }

        if (response.getStatusLine().toString().equals("HTTP/1.1 503 Service Unavailable")) {
            throw new HttpException("Registration server overload.");
        }

        String enqueueResponse = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
        response.getEntity().getContent().close(); // necessary ?

        log.accept("Enqueue response : "+enqueueResponse);

        ElastixJobQueueServlet.WaitingJob job = new Gson().fromJson(enqueueResponse, ElastixJobQueueServlet.WaitingJob.class);

        jobId = job.jobId;

        enqueueJobRequest = new HttpPost(serverUrlQueue+"?id="+job.jobId);

        while (job.waitingTimeInMs!=0) {
            try {
                Thread.sleep(job.waitingTimeInMs);
            } catch (InterruptedException e) {
                log.accept("RemoteElastixTask interrupted");
                throw new InterruptedException("Remote Elastik Task interrupted");
            }
            try {
                response = httpclient.execute(enqueueJobRequest);
            } catch (ClientProtocolException e) {
                e.printStackTrace();
                throw new HttpException("["+extraInfo+"] Server queueing registration failed with error message : "+e.getMessage());
            }

            enqueueResponse = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            response.getEntity().getContent().close(); // necessary ?

            log.accept("Enqueue response : "+enqueueResponse);
            job = new Gson().fromJson(enqueueResponse, ElastixJobQueueServlet.WaitingJob.class);
        }

        HttpPost httppost = new HttpPost(serverUrl+"?id="+jobId);

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

        File fixedImageFile = new File(settings.fixedImagePathSupplier.get());
        FileBody fixedImageBody = new FileBody(fixedImageFile, ContentType.DEFAULT_BINARY);
        builder.addPart(ElastixServlet.FixedImageTag, fixedImageBody);

        File movingImageFile = new File(settings.movingImagePathSupplier.get());
        FileBody movingImageBody = new FileBody(movingImageFile, ContentType.DEFAULT_BINARY);
        builder.addPart(ElastixServlet.MovingImageTag, movingImageBody);

        if (settings.initialTransformFilePath!=null) {
            File initialTransformFile = new File(settings.initialTransformFilePath);
            FileBody initialTransformationBody = new FileBody(initialTransformFile, ContentType.DEFAULT_TEXT);
            builder.addPart(ElastixServlet.InitialTransformTag, initialTransformationBody);
        }

        builder.addTextBody(ElastixServlet.NumberOfTransformsTag, new Integer(settings.transformationParameterPathSupplier.size()).toString());

        int indexTransformationParameter = 0;
        for (Supplier<String> s : settings.transformationParameterPathSupplier) {
            File transformationParameterFile = new File(s.get());
            FileBody transformationParameterBody = new FileBody(transformationParameterFile, ContentType.DEFAULT_TEXT);
            builder.addPart(ElastixServlet.TransformParameterTag(indexTransformationParameter), transformationParameterBody);
            indexTransformationParameter++;
        }

        HttpEntity entity = builder.build();
        httppost.setEntity(entity);

        log.accept(">>> Client sending Registration Request");

        try {
            response = httpclient.execute(httppost);
        } catch (ClientProtocolException e) {
            e.printStackTrace();
            throw new HttpException("Server registration failed with error message : "+e.getMessage());
        }
        log.accept(">>> Client received response status "+response.getStatusLine());

        if (response.getStatusLine().toString().equals("HTTP/1.1 200 OK")) {

            log.accept(">>> Client received result of registration request");

            InputStream is = response.getEntity().getContent();
            File zipAns = new File(settings.outputFolderSupplier.get(), "registration_result.zip");
            FileOutputStream fos = new FileOutputStream(zipAns);

            int read = 0;
            byte[] buffer = new byte[32768];
            while ((read = is.read(buffer)) > 0) {
                fos.write(buffer, 0, read);
            }

            fos.close();
            is.close();

            log.accept(">>> Client received all of registration request");
            log.accept(settings.outputFolderSupplier.get());

            File destDir = new File(settings.outputFolderSupplier.get());
            ZipInputStream zis = new ZipInputStream(new FileInputStream(zipAns));
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = newFile(destDir, zipEntry);
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    // fix for Windows-created archives
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }

                    // write file content
                    fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
            zis.close();

            zipAns.delete();
        } else {
            throw new HttpException("Server registration failed with status line : "+response.getStatusLine());
        }

    }

    public static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }
}
