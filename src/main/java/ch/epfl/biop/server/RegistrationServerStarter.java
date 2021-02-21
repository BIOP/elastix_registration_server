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

        StressTest();
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
