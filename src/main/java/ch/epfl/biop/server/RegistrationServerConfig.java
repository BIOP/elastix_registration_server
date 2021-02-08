package ch.epfl.biop.server;

public class RegistrationServerConfig {

    transient final public String currentRegistrationServerVersion = "0.1.0-SNAPSHOT";

    public String version = "0.1.0-SNAPSHOT";

    public String elaxtixLocation = "C:\\elastix-5.0.1-win64\\elastix.exe";

    public String transformixLocation = "C:\\elastix-5.0.1-win64\\transformix.exe";

    public int localPort = 8090;

    public int requestTimeOutInMs = 50000;

    public int maxNumberOfSimultaneousRequests = 4;

    public String jobsDataLocation = "src/test/resources/tmp/";

    public int initialElastixJobIndex = 0;

    public int initialTransformixIndex = 0;

}
