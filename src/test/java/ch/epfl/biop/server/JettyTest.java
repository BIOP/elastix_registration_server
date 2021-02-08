package ch.epfl.biop.server;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

public class JettyTest {
    private static RegistrationServer jettyServer;

    /*@BeforeClass
    public static void setup() throws Exception {
        jettyServer = new JettyServer();
        jettyServer.start();
    }

    @AfterClass
    public static void cleanup() throws Exception {
        jettyServer.stop();
    }

    @Test
    public void givenServer_whenSendRequestToBlockingServlet_thenReturnStatusOK() throws Exception {
        // given
        String url = "http://localhost:8090/status";
        HttpClient client = HttpClientBuilder.create().build();
        HttpGet request = new HttpGet(url);
        HttpResponse response = client.execute(request);

        // then
        assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);

    }

    @Test
    public void givenServer_whenSendRequestToNonBlockingServlet_thenReturnStatusOK() throws Exception {
        // when
        String url = "http://localhost:8090/heavy/async";
        HttpClient client = HttpClientBuilder.create().build();
        HttpGet request = new HttpGet(url);
        HttpResponse response = client.execute(request);

        // then
        assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
        String responseContent = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
        assertThat(responseContent).isEqualTo("This is some heavy resource that will be served in an async way");
    }

    @Test
    public void uploadFileTest() throws Exception {
        // https://www.baeldung.com/httpclient-multipart-upload
        String url = "http://localhost:8090/register";
        HttpClient httpclient = HttpClientBuilder.create().build();
        HttpPost httppost = new HttpPost(url);

        File atlasImage = new File("src/test/resources/atlas.tif");
        File sliceImage = new File("src/test/resources/unregistered.tif");

        FileBody atlasImageBody = new FileBody(atlasImage, ContentType.DEFAULT_BINARY);
        FileBody sliceImageBody = new FileBody(sliceImage, ContentType.DEFAULT_BINARY);

        //
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
        builder.addPart("atlasImage", atlasImageBody);
        builder.addPart("sliceImage", sliceImageBody);
        HttpEntity entity = builder.build();
//
        httppost.setEntity(entity);
        HttpResponse response = httpclient.execute(httppost);

        // Request parameters and other properties.
        /*List<NameValuePair> params = new ArrayList<>(2);
        params.add(new BasicNameValuePair("param-1", "12345"));
        params.add(new BasicNameValuePair("param-2", "Hello!"));

        params.add(new BasicNameValuePair("param-2", "Hello!"));
        httppost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));

        //Execute and get the response.
        HttpResponse response = httpclient.execute(httppost);
        HttpEntity entity = response.getEntity();

        if (entity != null) {
            try (InputStream instream = entity.getContent()) {
                // do something useful
            }
        }
    }*/
}
