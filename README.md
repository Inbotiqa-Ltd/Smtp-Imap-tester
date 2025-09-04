# Smtp-Imap-tester
Overview

This project provides two utilities to test mail connections using OAuth 2.0, each designed for a different environment. All mail server settings (host, port, protocols) are now fully configurable in the mail.properties file.

    Authorization Code Flow (smtp-tester-auth-code-flow.jar): For use on machines with a local web browser.

    Device Code Flow (smtp-tester-device-code-flow.jar): For use on headless machines without a browser (e.g., a remote Linux server via SSH).

1. Prerequisite: Configure OAuth 2.0 Credentials

2. Project Structure

3. Build the Project

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
