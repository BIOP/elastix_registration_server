package ch.epfl.biop.server;

import org.apache.commons.io.FilenameUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class ServletUtils {

    static String copyFileToServer(String tempFileFolder, HttpServletRequest request, String tag, String fileNameOut) throws IOException, ServletException {
        String pathFileOut = null;
        Part part = request.getPart(tag);
        String fileNameIn = part.getSubmittedFileName();
        String fileExtension = FilenameUtils.getExtension(fileNameIn);
        pathFileOut = tempFileFolder + fileNameOut + "." + fileExtension;
        System.out.println(fileNameIn + " > " + pathFileOut);
        Files.copy(part.getInputStream(), Paths.get(pathFileOut),
                StandardCopyOption.REPLACE_EXISTING);
        return pathFileOut;
    }
}
