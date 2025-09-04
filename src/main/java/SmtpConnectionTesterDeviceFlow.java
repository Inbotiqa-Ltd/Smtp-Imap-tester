import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.json.JSONObject;

/**
 * A utility to test mail connections using the OAuth 2.0 Device Authorization Flow.
 * This flow is designed for headless environments like remote servers.
 */
public class SmtpConnectionTesterDeviceFlow {

    public static void main(String[] args) throws Exception {
        // --- 1. Load Configuration ---
        Properties props = loadProperties();
        if (props == null) return;

        final String userEmail = props.getProperty("mail.user");
        final String sharedMailbox = props.getProperty("mail.shared.mailbox.address");
        
        // --- 2. Get Access Token via Device Code Flow ---
        System.out.println("--- Starting OAuth 2.0 Device Code Flow ---");
        String accessToken = getAccessTokenViaDeviceFlow(props);

        if (accessToken == null) {
            System.err.println("\nFailed to obtain access token. Exiting.");
            return;
        }
        System.out.println("\nSuccessfully obtained access token.");
        System.out.println("Access Token: "+accessToken);
        // --- 3. Run Connection Tests ---
        // We can reuse the test methods from the other class.
        SmtpConnectionTester.runConnectionTests(props, userEmail, sharedMailbox, accessToken);
    }

    /**
     * Obtains an access token using the OAuth 2.0 Device Code Flow.
     */
    private static String getAccessTokenViaDeviceFlow(Properties props) throws IOException, InterruptedException {
        // Part 1: Request a device and user code
        String deviceCodeUrl = props.getProperty("mail.oauth.devicecode.url");
        String clientId = props.getProperty("mail.oauth.client.id");
        String scope = props.getProperty("mail.oauth.scope", "").replace('+', ' ').replaceAll("\\s+", " ").trim();
        
        String deviceCodePayload = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                                 "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(deviceCodeUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(deviceCodePayload.getBytes());
        }

        JSONObject deviceCodeResponse;
        try (InputStream is = conn.getInputStream()) {
            deviceCodeResponse = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Failed to get device code. Check your client_id and device code URL.");
            try(InputStream es = conn.getErrorStream()) {
                if (es != null) {
                    System.err.println("Error details: " + new String(es.readAllBytes()));
                }
            }
            throw e;
        }

        String userCode = deviceCodeResponse.getString("user_code");
        String deviceCode = deviceCodeResponse.getString("device_code");
        String verificationUri = deviceCodeResponse.getString("verification_uri");
        int interval = deviceCodeResponse.getInt("interval");
        int expiresIn = deviceCodeResponse.getInt("expires_in");

        // Part 2: Display instructions to the user
        System.out.println("\n--- User Action Required ---");
        System.out.println("To sign in, use a web browser to open the page:");
        System.out.println(verificationUri);
        System.out.println("And enter the code to authenticate:");
        System.out.println(userCode);
        System.out.println("\nWaiting for you to authenticate in the browser...");

        // Part 3: Poll for the token
        long startTime = System.currentTimeMillis();
        String tokenUrl = props.getProperty("mail.oauth.token.url");
        String clientSecret = props.getProperty("mail.oauth.client.secret");

        // Build the polling payload
        StringBuilder tokenPayloadBuilder = new StringBuilder();
        tokenPayloadBuilder.append("grant_type=urn:ietf:params:oauth:grant-type:device_code");
        tokenPayloadBuilder.append("&client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        tokenPayloadBuilder.append("&device_code=").append(URLEncoder.encode(deviceCode, StandardCharsets.UTF_8));
        
        // *** MODIFICATION START ***
        // Add client_secret if it exists. This handles the AADSTS7000218 error for app registrations 
        // that are not correctly configured as public clients. The recommended fix is to enable
        // "Allow public client flows" in the Azure App Registration.
        if (clientSecret != null && !clientSecret.trim().isEmpty()) {
             tokenPayloadBuilder.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
        }
        // *** MODIFICATION END ***
        String tokenPayload = tokenPayloadBuilder.toString();

        while ((System.currentTimeMillis() - startTime) < (expiresIn * 1000L)) {
            Thread.sleep(interval * 1000L); 

            HttpURLConnection tokenConn = (HttpURLConnection) new URL(tokenUrl).openConnection();
            tokenConn.setRequestMethod("POST");
            tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            tokenConn.setDoOutput(true);
            try (OutputStream os = tokenConn.getOutputStream()) {
                os.write(tokenPayload.getBytes());
            }

            if (tokenConn.getResponseCode() == 200) {
                try (InputStream is = tokenConn.getInputStream()) {
                    JSONObject tokenResponse = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                    return tokenResponse.getString("access_token");
                }
            } else {
                try (InputStream es = tokenConn.getErrorStream()) {
                    if (es != null) {
                        String errorBody = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                        JSONObject errorResponse = new JSONObject(errorBody);
                        if (!"authorization_pending".equals(errorResponse.optString("error"))) {
                            System.err.println("Error polling for token: " + errorResponse.toString(2));
                            return null;
                        }
                    }
                }
            }
        }

        System.err.println("Authentication timed out.");
        return null;
    }

    /**
     * Loads configuration from the mail.properties file.
     */
    private static Properties loadProperties() {
        String propertiesFilePath = "mail.properties";
        System.out.println("Attempting to read configuration from: " + propertiesFilePath);
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(propertiesFilePath)) {
            props.load(input);
            System.out.println("Successfully loaded properties.");
            return props;
        } catch (IOException ex) {
            System.err.println("Error: Could not find or read 'mail.properties' file.");
            ex.printStackTrace();
            return null;
        }
    }
}
