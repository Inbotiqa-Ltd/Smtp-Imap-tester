# Smtp-Imap-tester
Overview

This project provides two utilities to test mail connections using OAuth 2.0, each designed for a different environment. All mail server settings (host, port, protocols) are now fully configurable in the mail.properties file.

    Authorization Code Flow (smtp-tester-auth-code-flow.jar): For use on machines with a local web browser.

    Device Code Flow (smtp-tester-device-code-flow.jar): For use on headless machines without a browser (e.g., a remote Linux server via SSH).

1. Prerequisite: Configure OAuth 2.0 Credentials

You must register your application with Microsoft 365 to get a Client ID.
Instructions for Microsoft 365 / Outlook

    Go to the Microsoft Entra admin center.

    Navigate to Identity -> Applications -> App registrations and create a + New registration.

    Give it a name (e.g., "Mail Connection Tester").

    Under Supported account types, select the appropriate option.

    For Authorization Code Flow: Under Redirect URI, select Web and enter the URI from your mail.properties file (e.g., http://localhost:8888/callback).

    For Device Code Flow: You can skip the Redirect URI configuration.

    Click Register.

    On the app's Overview page, copy the Application (client) ID.

    Navigate to Certificates & secrets to create and copy your Client Secret (needed for Authorization Code Flow, but can also be used by Device Code Flow as a fallback).

    Navigate to API permissions and add the following Delegated permissions from Microsoft Graph:

        offline_access, User.Read, SMTP.Send, IMAP.AccessAsUser.All, Mail.Send.Shared

    CRITICAL for Device Code Flow: Navigate to the Authentication tab. Scroll down and under Advanced settings, set Allow public client flows to Yes. Click Save. This is the recommended setting for the device flow.

2. Project Structure

Place your two Java files (SmtpConnectionTester.java and SmtpConnectionTesterDeviceFlow.java) inside src/main/java/.
3. Build the Project

    Prerequisites: JDK 11+, Maven.

    Build: Open a terminal in the project root and run:

    mvn clean package

    This will create two JAR files in the target/ directory:

        smtp-tester-auth-code-flow.jar

        smtp-tester-device-code-flow.jar

4. Configure and Run

    Create mail.properties: Use the provided template to create your properties file. You can now configure all user, OAuth, SMTP, and IMAP settings in this file.

    Choose the right JAR for your environment and copy it along with mail.properties to your execution directory.

A) Running the Authorization Code Flow (Requires a Browser)

    Run the command:

    java -jar smtp-tester-auth-code-flow.jar

    A browser window should open automatically. Log in and grant consent.

B) Running the Device Code Flow (For Headless Servers)

    Run the command on your remote server:

    java -jar smtp-tester-device-code-flow.jar

    The application will display a URL and a user code.

    On your local machine, open the URL in a browser and enter the code to authenticate.
