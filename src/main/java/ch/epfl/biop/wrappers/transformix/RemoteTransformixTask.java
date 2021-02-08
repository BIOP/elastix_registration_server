package ch.epfl.biop.wrappers.transformix;

import ch.epfl.biop.server.ElastixServlet;
import ch.epfl.biop.server.TransformixServlet;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.*;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static ch.epfl.biop.server.RegistrationServer.TRANSFORMIX_PATH;
import static ch.epfl.biop.wrappers.elastix.RemoteElastixTask.newFile;

public class RemoteTransformixTask extends TransformixTask {

    String serverUrl;

    public RemoteTransformixTask(String serverUrl) {
        this.serverUrl = serverUrl + TRANSFORMIX_PATH;
    }

    @Override
    public void run() throws Exception {
        /*DefaultTransformixTask tt = new DefaultTransformixTask();
        tt.setSettings(settings);
        tt.run();*/

        int timeout = 50;
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeout * 1000)
                .setConnectionRequestTimeout(timeout * 1000)
                .setSocketTimeout(timeout * 1000).build();
        CloseableHttpClient httpclient =
                HttpClientBuilder.create().setDefaultRequestConfig(config).build();

        HttpPost httppost = new HttpPost(serverUrl);

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

        File inputPtsFile = new File(settings.inputPtsFileSupplier.get());
        FileBody inputPtsBody = new FileBody(inputPtsFile, ContentType.DEFAULT_BINARY);
        builder.addPart(TransformixServlet.InputPtsFileTag, inputPtsBody);

        File transformFile = new File(settings.transformFileSupplier.get());
        FileBody transformBody = new FileBody(transformFile, ContentType.DEFAULT_BINARY);
        builder.addPart(TransformixServlet.TransformFilesTag, transformBody);

        HttpEntity entity = builder.build();
        httppost.setEntity(entity);
        HttpResponse response = httpclient.execute(httppost);
        InputStream is = response.getEntity().getContent();
        File zipAns = new File(settings.outputFolderSupplier.get(), "registration_result.zip");
        FileOutputStream fos = new FileOutputStream(zipAns);

        int read = 0;
        byte[] buffer = new byte[32768];
        while( (read = is.read(buffer)) > 0) {
            fos.write(buffer, 0, read);
        }

        fos.close();
        is.close();

        System.out.println(settings.outputFolderSupplier.get());

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

    }

}
