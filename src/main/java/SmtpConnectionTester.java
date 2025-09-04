import java.awt.Desktop;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONObject;

import com.sun.net.httpserver.HttpServer;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * A utility to test mail connections using the OAuth 2.0 Authorization Code Flow.
 * This flow requires a local web browser.
 */
public class SmtpConnectionTester {

    private static String authorizationCode = null;
    private static final CountDownLatch latch = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        // --- 1. Load Configuration ---
        Properties props = loadProperties();
        if (props == null) return;

        final String userEmail = props.getProperty("mail.user");
        final String sharedMailbox = props.getProperty("mail.shared.mailbox.address");
        
        // --- 2. Get Authorization Code via Browser Flow ---
        getAuthorizationCode(props);

        if (authorizationCode == null) {
            System.err.println("Could not obtain authorization code. Exiting.");
            return;
        }
        System.out.println("Successfully obtained authorization code.");

        // --- 3. Exchange Authorization Code for Access Token ---
        System.out.println("\nExchanging authorization code for access token...");
        String accessToken = exchangeCodeForAccessToken(props);
        if (accessToken == null) {
            System.err.println("Could not obtain access token. Exiting.");
            return;
        }
        System.out.println("Successfully obtained access token.");
        System.out.println("Access Token: "+accessToken);
        // --- 4. Run Connection Tests ---
        runConnectionTests(props, userEmail, sharedMailbox, accessToken);
    }

     /**
     * Starts a local server, opens a browser for user consent, and captures the authorization code.
     */
    private static void getAuthorizationCode(Properties props) throws IOException {
        URI redirectUri = URI.create(props.getProperty("mail.oauth.redirect.uri"));
        int port = redirectUri.getPort();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(redirectUri.getPath(), httpExchange -> {
            String query = httpExchange.getRequestURI().getQuery();
            if (query != null && query.contains("code=")) {
                String[] params = query.split("&");
                for (String param : params) {
                    if (param.startsWith("code=")) {
                        authorizationCode = param.substring(5);
                        break;
                    }
                }
                String response = "<html><body><h1>Authorization successful!</h1><p>You can close this browser tab now.</p></body></html>";
                httpExchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = httpExchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } else {
                 String errorResponse = "<html><body><h1>Authorization Failed</h1><p>No authorization code was found in the request.</p></body></html>";
                 httpExchange.sendResponseHeaders(400, errorResponse.length());
                 try (OutputStream os = httpExchange.getResponseBody()) {
                    os.write(errorResponse.getBytes());
                }
            }
            latch.countDown();
        });
        server.setExecutor(null);
        server.start();
        System.out.println("Local server started on port " + port);

        String scope = props.getProperty("mail.oauth.scope", "").replace('+', ' ').replaceAll("\\s+", " ").trim();
        String authUrl = props.getProperty("mail.oauth.auth.url") + "?" +
                "client_id=" + URLEncoder.encode(props.getProperty("mail.oauth.client.id"), StandardCharsets.UTF_8) + "&" +
                "redirect_uri=" + URLEncoder.encode(redirectUri.toString(), StandardCharsets.UTF_8) + "&" +
                "response_type=code&" +
                "scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8) + "&" +
                "access_type=offline";

        System.out.println("\n--- User Action Required ---");
        System.out.println("Please open the following URL in your browser, log in, and grant permissions:");
        System.out.println("\n" + authUrl + "\n");

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(authUrl));
                System.out.println("Your default browser should have opened for authentication.");
            }
        } catch (Exception e) {
            System.err.println("Could not automatically open browser. Please copy/paste the URL manually.");
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Thread interrupted while waiting for authorization.");
        }
        server.stop(1);
        System.out.println("Local server stopped.");
    }

    /**
     * Exchanges the authorization code for an access token.
     */
    private static String exchangeCodeForAccessToken(Properties props) throws IOException {
        URL url = new URL(props.getProperty("mail.oauth.token.url"));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        String params = Stream.of(
                new String[]{"client_id", props.getProperty("mail.oauth.client.id")},
                new String[]{"client_secret", props.getProperty("mail.oauth.client.secret")},
                new String[]{"code", authorizationCode},
                new String[]{"grant_type", "authorization_code"},
                new String[]{"redirect_uri", props.getProperty("mail.oauth.redirect.uri")}
        ).map(p -> p[0] + "=" + URLEncoder.encode(p[1], StandardCharsets.UTF_8))
         .collect(Collectors.joining("&"));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(params.getBytes());
        }

        try (InputStream is = conn.getInputStream()) {
            String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(response);
            return json.getString("access_token");
        } catch (Exception e) {
            System.err.println("Error exchanging code for token: " + e.getMessage());
            try (InputStream es = conn.getErrorStream()) {
                if (es != null) {
                    System.err.println("Error details: " + new String(es.readAllBytes()));
                }
            }
            return null;
        }
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

    /**
     * Runs the sequence of connection tests.
     */
    public static void runConnectionTests(Properties props, String userEmail, String sharedMailbox, String accessToken) {
        System.out.println("\n--- SMTP Connection Test ---");
        boolean smtpSuccess = testSmtpConnection(props, userEmail, accessToken);

        boolean sharedMailboxAccessSuccess = false;
        if (smtpSuccess && sharedMailbox != null && !sharedMailbox.trim().isEmpty()) {
            System.out.println("\n--- Shared Mailbox Read Access Test (IMAP) ---");
            sharedMailboxAccessSuccess = testSharedMailboxConnection(props, userEmail, accessToken, sharedMailbox);

            if (sharedMailboxAccessSuccess) {
                System.out.println("\n--- Shared Mailbox Send As Test (SMTP) ---");
                testSendFromSharedMailbox(props, userEmail, accessToken, sharedMailbox);
            }
        } else if (sharedMailbox == null || sharedMailbox.trim().isEmpty()) {
             System.out.println("\nSkipping shared mailbox tests as 'mail.shared.mailbox.address' is not set.");
        }
    }

    /**
     * Connects to the SMTP server using the provided access token.
     */
    private static boolean testSmtpConnection(Properties props, String userEmail, String accessToken) {
        // *** MODIFICATION: All mail properties are now read directly from the 'props' object ***
        final String host = props.getProperty("mail.smtp.host");
        final int port = Integer.parseInt(props.getProperty("mail.smtp.port"));
        System.out.println("SMTP Host: " + host + ", Port: " + port + ", User: " + userEmail);

        Session session = Session.getInstance(props);
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(host, port, userEmail, accessToken);
            System.out.println("\nSUCCESS: SMTP Connection established successfully!");
            return true;
        } catch (AuthenticationFailedException e) {
            System.err.println("\nERROR: SMTP OAuth Authentication failed. The access token may be invalid, expired, or not have the correct scope (e.g., SMTP.Send).");
        } catch (MessagingException e) {
            System.err.println("\nERROR: Failed to connect to the SMTP server. Check host, port, and network connectivity.");
        }
        return false;
    }

    /**
     * Connects to a shared mailbox via IMAP using the provided access token.
     */
    private static boolean testSharedMailboxConnection(Properties props, String userEmail, String accessToken, String sharedMailbox) {
        // *** MODIFICATION: All mail properties are now read directly from the 'props' object ***
        final String imapHost = props.getProperty("mail.imap.host");
        final int imapPort = Integer.parseInt(props.getProperty("mail.imap.port"));

        // For Microsoft, the IMAP username for a shared mailbox is formatted as user@domain\shared@domain
        //final String imapUser = userEmail + "\\" + sharedMailbox;
        System.out.println("Attempting IMAP connection to " + sharedMailbox + " using user " + userEmail);
        System.out.println("IMAP Host: " + imapHost + ", Port: " + imapPort + ", Login User: " + userEmail);

        Session session = Session.getInstance(props);
        try (Store store = session.getStore("imap")) {
            store.connect(imapHost, imapPort, sharedMailbox, accessToken);
            try (Folder inbox = store.getFolder("INBOX")) {
                inbox.open(Folder.READ_ONLY);
                System.out.println("INBOX folder opened successfully. Message count: " + inbox.getMessageCount());
            }
            System.out.println("\nSUCCESS: Shared Mailbox read access test passed for " + sharedMailbox);
            return true;
        } catch (AuthenticationFailedException e) {
            System.err.println("\nERROR: Shared Mailbox (IMAP) OAuth Authentication failed.");
            System.err.println("Verify the user " + userEmail + " has delegate permissions on the mailbox " + sharedMailbox + ".");
            System.err.println("Ensure your app has the required API permissions (scopes) like 'IMAP.AccessAsUser.All'.");
        } catch (MessagingException e) {
            System.err.println("\nERROR: Failed to connect to the Shared Mailbox. Check IMAP settings and network connectivity.");
        }
        return false;
    }

    /**
     * Attempts to send an email FROM the shared mailbox.
     */
    private static void testSendFromSharedMailbox(Properties props, String userEmail, String accessToken, String sharedMailbox) {
        final String testRecipient = props.getProperty("mail.test.recipient");
        if (testRecipient == null || testRecipient.trim().isEmpty()) {
            System.out.println("Skipping Send As test because 'mail.test.recipient' is not set in properties file.");
            return;
        }

        System.out.println("Attempting to send a test email from '" + sharedMailbox + "' to '" + testRecipient + "'");
        
        Session session = Session.getInstance(props);
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(sharedMailbox));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(testRecipient));
            message.setSubject("OAuth Connection Tester - Send As Test");
            message.setText("This is a test email sent from the OAuth Connection Tester utility to verify 'Send As' permissions for user " + userEmail + " on behalf of " + sharedMailbox);
            message.setSentDate(new Date());

            try (Transport transport = session.getTransport("smtp")) {
                transport.connect(props.getProperty("mail.smtp.host"), Integer.parseInt(props.getProperty("mail.smtp.port")), userEmail, accessToken);
                transport.sendMessage(message, message.getAllRecipients());
                System.out.println("\n==================================================================");
                System.out.println("SUCCESS: Send As test passed. Email sent successfully.");
                System.out.println("Check the inbox of '" + testRecipient + "' for the test message.");
                System.out.println("==================================================================");
            }
        } catch (AuthenticationFailedException e) {
            System.err.println("\nERROR: Send As test failed during authentication.");
            System.err.println("This can happen if the token is valid but does not have the 'Mail.Send.Shared' or equivalent scope.");
            e.printStackTrace();
        } catch (MessagingException e) {
            System.err.println("\nERROR: Send As test failed. The server rejected the request.");
            System.err.println("Verify that the user '" + userEmail + "' has 'Send As' or 'Send on Behalf' permissions for the mailbox '" + sharedMailbox + "'.");
            System.err.println("Also ensure the application has the 'Mail.Send.Shared' API permission.");
            e.printStackTrace();
        }
    }
}
