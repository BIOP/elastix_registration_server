package ch.epfl.biop.server;

import ch.epfl.biop.wrappers.elastix.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Command to start the server :
*
* java -Xmx1g -jar biop_registration_server-x.y.z.jar
* of course replace x, y, z by the current version (and don't forget -SNAPSHOT in the version if necessary for testing)
* A json config file placed in the same folder as the jar can be used to configure the server
* To see the format, just launch once the server and copy the model displayed as an output in the console
*
*/

public class RegistrationServerStarter {

    public static void main(String... args) {
        try {

            System.out.println("================================");
            RegistrationServerConfig config;
            Gson gson = new GsonBuilder().setPrettyPrinting().create();

            if ((args == null)||(args.length==0)) { // No args
                config = new RegistrationServerConfig();
                System.out.println("No args, using default configuration json file:");
                System.out.println("---------------------------------");
                System.out.println(gson.toJson(config));
                System.out.println("---------------------------------");
            } else {
                // create a reader
                Reader reader = Files.newBufferedReader(Paths.get(args[0]));
                config = gson.fromJson(reader, RegistrationServerConfig.class);
            }

            RegistrationServer registrationServer = new RegistrationServer(config);

            System.out.println("--- Starting registration server ");
            registrationServer.start(config.localPort);

            System.out.println("--- Registration server started ");
            System.out.println("================================");

        } catch (Exception e) {
            e.printStackTrace();
        }

        //StressTest();
    }

    public static void StressTest() {
        for (int i=0;i<10;i++){
            final int iFinal = i;
            new Thread(() -> {
                try {
                    TestRegister(iFinal);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    private static void TestRegister(int idx) throws Exception {

        RegistrationParameters rp = new RegParamAffine_Fast();

        if (!new File("src/test/resources/out/"+idx+"/").exists()) {
            Files.createDirectory(Paths.get("src/test/resources/out/" + idx + "/"));
        }

        ElastixTaskSettings settings = new ElastixTaskSettings()
                .fixedImage(() -> "src/test/resources/atlas.tif")
                .movingImage(() -> "src/test/resources/unregistered.tif")
                .addTransform(() -> RegisterHelper.getFileFromRegistrationParameters(rp))
                .outFolder(()-> "src/test/resources/out/"+idx+"/");

        ElastixTask remoteTask = new RemoteElastixTask("http://localhost:8090", ""+idx);
        remoteTask.setSettings(settings);
        remoteTask.run();
    }

}
