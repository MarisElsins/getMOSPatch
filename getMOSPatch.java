/*
File name:          getMOSPatch.java
Version:            2.0 
Purpose:            An easier way to download patches from My Oracle Support (MOS) https://support.oracle.com
                    All you need is:
                        - Valid MOS credentials
                        - the patch number(s)
                        - list of target platform/languages that the patch is required for
                        - internet connectivity
                        - JRE 1.6 or higher

Author:             Maris Elsins (elmaris@gmail.com)
Copyright:          (c) Maris Elsins - https://me-dba.com - All rights reserved.

Disclaimer:         This script is provided "as is", so no warranties or guarantees are made
                    about its correctness, reliability and safety. Use it at your own risk!

License:            1) You may use this script for your (or your businesses) purposes for free
                    2) You may modify this script as you like for your own (or your businesses) purpose,
                       but you must always leave this script header (the entire comment section), including the
                       author, copyright and license sections as the first thing in the beginning of this file
                    3) You may NOT publish or distribute this script, java classes compiled from it, or packaged jar files,
                       or any other variation of it PUBLICLY (including, but not limited to uploading it to your public website or ftp server),
                       instead just link to its location in https://github.com/MarisElsins/getMOSPatch
                    4) You may distribute this script INTERNALLY in your company, for internal use only,
                       for example when building a standard DBA toolset to be deployed to all
                       servers or DBA workstations

Usage:
        java -jar getMOSPatch.jar patch=<patch_number_1>[,<patch_number_n>]* [reset=yes] [platform=<plcode_1>[,<plcode_n>]*] [regexp=<regular_expression>] [download=all] [MOSUser=<username>] [MOSPass=<password>]

        Note 1: for JRE 1.6: use java -Dhttps.protocols=TLSv1 -jar getMOSPatch.jar ...
        Note 2: Usage notes are provided for a packaged jre
        Note 3: Order of parameters is irrelevant

                    patch -         list of patches to download, i.e. 6880880,16867777,12978712
                    reset=yes -     This will initiate the resetting of the chosen Platforms/Languages, otherwise the list previous time used is retrieved from .getMOSPatch.cfg
                    platform -      List of comma separated platform language codes The code list is presented the first time you execute the script.,
                                    i.e. "226P,3L" for Linux x86-64 and Canadian French (FRC)
                    regexp -        regular expression to filter the filenames. Typically this can be used if the same patch is available for multiple releases of software and you know which one you need.
                                    i.e. .*121.* would be useful for Oracle Database 12c (R1)
                    download=yes -  specify to download all found files without need to specify inputs. Very useful when "regexp" parameter is used
                    MOSUser -       Optionally specify the MOS username, if not provided, it will be prompted.
                    MOSPass -       Optionally specify the MOS pasword, if not provided, it will be prompted.

Example:            To download OPatch for 11gR2 database on Linux x86-64:

        $ java -jar getMOSPatch.jar MOSUser=elsins@nomail.com patch=6880880 regexp=.*1120.* download=all
        Enter your MOS password:

        We're going to download patches for the following Platforms/Languages:
         226P - Linux x86-64

        Processing patch 6880880 for Linux x86-64 and applying regexp .*1120.* to the filenames:
         1 - p6880880_112000_Linux-x86-64.zip
         Enter Comma separated files to download: all
         All files will be downloadad because download=all was specified.

        Downloading all selected files
         Downloading p6880880_112000_Linux-x86-64.zip: 50MB at average speed of 3116KB/s - DONE!
*/

// Using only the basic stuff that's included in JRE, to minimize the prerequisites
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class getMOSPatch {
    //Constants section
    private static final int BUFFER_SIZE = 8192;
    private static final int PROGRESS_INTERVAL = 1 * 1024 * 1024;
    private static final char[] pchar = {
        '-', '\\', '|', '/'
    };

    // some variables, that will be used for returning from classes.
    private static String outputstring;
    private static InputStream iStream;

    // I'll store the passed parameters in this Map
    private static Map < String, String > parameters;

    // ConfiguredPlatforms holds the list of configured Platforms/Languages
    private static Map < String, String > ConfiguredPlatforms = new HashMap < String, String > ();

    // DownloadFiles contains URLs to download
    private static Map < Integer, String > DownloadFiles = new TreeMap < Integer, String > ();
    private static int DownloadFilesCounter = 0;

    // Intermediate MAP that populates the URLs for specific patch for the time inputs are collected.
    private static Map < Integer, String > PatchFileList = new TreeMap < Integer, String > ();

    //Authenticator that requests the username password inputs
    private static class CustomAuthenticator extends Authenticator {
        // Called when password authorization is needed
        protected PasswordAuthentication getPasswordAuthentication() {
            String username;
            char[] password;
            Console console = System.console();
            // If username is provided via a parameter then use it once
            if (parameters.get("MOSUser") == null) {
                username = console.readLine("Enter your MOS username: ");
            } else {
                username = parameters.get("MOSUser");
                parameters.remove("MOSUser");
            }
            // If password is provided via a parameter then use it once
            if (parameters.get("MOSPass") == null) {
                password = console.readPassword("Enter your MOS password: ");
            } else {
                password = parameters.get("MOSPass").toCharArray();
                parameters.remove("MOSPass");
            }
            return new PasswordAuthentication(username, password);
        }
    }

    // Prepares the inputstream for HTTP downloads (webpages and files too)
    // "Heavily inspired" from Nathan Reynolds' post post: http://stackoverflow.com/revisions/26046079/2
    private static InputStream getHttpInputStream(String url) throws Exception {
        try {
            URL resourceUrl, base, next;
            HttpURLConnection conn;
            String location;
            location = url;

            while (true) {
                resourceUrl = new URL(url);
                conn = (HttpURLConnection)(new URL(location).openConnection());
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(false); // Make the logic below easier to detect redirections

                switch (conn.getResponseCode()) {
                    case HttpURLConnection.HTTP_MOVED_PERM:
                    case HttpURLConnection.HTTP_MOVED_TEMP:
                        location = conn.getHeaderField("Location");
                        base = new URL(url);
                        next = new URL(base, location); // Deal with relative URLs
                        url = next.toExternalForm();
                        continue;
                }
                break;
            }
            iStream = (InputStream) conn.getInputStream();
        } catch (Exception e) {
            throw e;
        }
        return iStream;
    }

    // method to download a file
    //
    private static void DownloadFile(String url, String filename) throws Exception {
        try {
            int filesize = 0, printsize = 0;
            long time_ms1, time_ms2;
            String progrdata = " ";
            InputStream content = (InputStream) getHttpInputStream(url);
            BufferedReader in = new BufferedReader(new InputStreamReader(content));
            FileOutputStream outputStream = new FileOutputStream(filename);

            int bytesRead = -1, iterator = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            // I'm using this hardcoded filename from webpage download.
            if (!filename.equals(".getMOSPatch.tmp")) {
                System.out.print("Downloading " + filename + ":  ");
            }
            // The download is happening here. I've pimped it with some progress display (except when downloading a webpage)
            time_ms1 = System.currentTimeMillis();
            while ((bytesRead = content.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                // Show extended download progress only if downloading a real file
                // I've seen this stuff sometimes not working on windows.
                if (!filename.equals(".getMOSPatch.tmp")) {
                    filesize = filesize + bytesRead;
                    if (printsize + PROGRESS_INTERVAL < filesize) {
                        time_ms2 = System.currentTimeMillis();
                        System.out.print(String.format("%" + progrdata.length() + "s", "").replace(" ", "\b"));
                        progrdata = pchar[(iterator++ % 4)] + " " + filesize / 1024 / 1024 + "MB at average speed of " + filesize / (time_ms2 - time_ms1) + "KB/s        ";
                        System.out.print(progrdata);
                        printsize = printsize + PROGRESS_INTERVAL;
                    }
                    // If downloading a webpage, just show a rotating char as a sign that something's ongoing.
                } else {
                    System.out.print(String.format("%" + progrdata.length() + "s", "").replace(" ", "\b") + pchar[(iterator++ % 4)]);
                }
            }
            // Download completed. In case of a real file post the final stats, otherwise remove the char.
            System.out.print(String.format("%" + progrdata.length() + "s", "").replace(' ', '\b'));
            if (!filename.equals(".getMOSPatch.tmp")) {
                time_ms2 = System.currentTimeMillis();
                progrdata = filesize / 1024 / 1024 + "MB at average speed of " + filesize / (time_ms2 - time_ms1) + "KB/s - DONE!";
                System.out.println(progrdata);
            } else {
                System.out.print("\b");
            }
            // close the sreams
            content.close();
            outputStream.close();
        } catch (Exception e) {
            throw e;
        }
    }

    // reads a file and returns a string
    private static class ReadFile {
        String everything;
        ReadFile(String filename) throws IOException {
            try {
                int i = 0;
                BufferedReader br = new BufferedReader(new FileReader(filename));
                StringBuilder sb = new StringBuilder();
                String line = br.readLine();
                while (line != null) {
                    if (i++ > 0) {
                        sb.append("\n");
                    }
                    sb.append(line);
                    line = br.readLine();
                }
                everything = sb.toString();
                br.close();
                //Pass the exception up for processing. We want to terminate if it breaks here.
            } catch (IOException e) {
                throw e;
            }
        }
        String getContent() {
            return everything;
        }
    }

    // downloads from URL into a String
    private static String DownloadString(String url) throws Exception {
        try {
            DownloadFile(url, ".getMOSPatch.tmp");
            outputstring = new ReadFile(".getMOSPatch.tmp").getContent();
            new File(".getMOSPatch.tmp").delete();
        } catch (Exception e) {
            throw e;
        }
        return outputstring;
    }

    // Validates that all values in the passed comma separated string exists in the Map<String, String>
    private static boolean CheckInputs(String inputs, Map < String, String > ArrayOfValues) {
        try {
            for (String p: inputs.split(",")) {
                if (ArrayOfValues.get(p) == null) {
                    return false;
                }
            }
            return true;
            // Any error in this method means the inputs were invalid, so just return false!
        } catch (Exception e) {
            return false;
        }
    }

    // Validates that all values in the passed comma separated string exists in the Map<Integer, String>
    private static boolean CheckInputsTree(String inputs, Map < Integer, String > ArrayOfValues) {
        // special processing for "all", as it's processed later
        if (inputs.equals("") || inputs.equals("all")) {
            return true;
        }
        for (String p: inputs.split(",")) {
            try {
                if (ArrayOfValues.get(Integer.parseInt(p)) == null) {
                    return false;
                }
                // Any error in this method means the inputs were invalid, so just return false!
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    // Method to populate the
    private static void Platforms() throws Exception {
        String getMOSPatchcfg = "", s, listplatforms = "";
        // this map is used to store platform/language codes and description from MOS.
        Map < String, String > platforms = new HashMap < String, String > ();

        // Reading the .getMOSPatch.cfg file into getMOSPatchcfg variable if the file exists
        try {
            getMOSPatchcfg = new ReadFile(".getMOSPatch.cfg").getContent();
        } catch (Exception e) {
            // Whatever
        }

        // Populate variables with available platforms
        // Checking if we need to download platform list from MOS, yes if:
        //  * parameter "reset=yes" was passed, or
        //  * "platforms" parameter was provided (we need to validate the input), or
        //  * the .getMOSPatch.cfg was empty or didn't exist
        if ((parameters.containsKey("reset") && parameters.get("reset").equals("yes")) || (parameters.containsKey("platform") || getMOSPatchcfg.length() == 0)) {
            if (parameters.get("platform") == null) {
                System.out.println("Platforms and languages need to be reset.");
                System.out.println("Obtaining the list of platforms and languages:");
            }
            // download the search page into variable s
            s = DownloadString("https://updates.oracle.com/Orion/SavedSearches/switch_to_simple");
            // Extract platforms/Languages list
            Pattern regex = Pattern.compile("<select name=plat_lang.*</select>", Pattern.DOTALL);
            Matcher regexMatcher = regex.matcher(s);
            if (regexMatcher.find()) {
                for (String oneline: regexMatcher.group(0).split("\\r?\\n")) {
                    if (oneline.contains("option") && !oneline.contains("selected")) {
                        if (parameters.get("platform") == null) {
                            System.out.println(oneline.split("\"")[1] + " - " + oneline.split(">")[1]);
                        }
                        // Put the downloaded platform codes and descriptions into the "platforms" Map
                        platforms.put(oneline.split("\"")[1], oneline.split(">")[1]);
                    }
                }
            }
            // Ask inputs if "platforms" parameter was not specified, and remove the parameter. SO a new value was asked if the inputs validation fails
            System.out.println();
            Console console = System.console();
            if (parameters.get("platform") == null) {
                listplatforms = console.readLine("Enter Comma separated platforms to list: ");
            } else {
                listplatforms = parameters.get("platform");
                System.out.println("Enter Comma separated platforms to list: " + listplatforms);
                parameters.remove("platform");

            }
            // check the inputs as many times as necessary
            while (!CheckInputs(listplatforms, platforms)) {
                System.out.println(" ERROR: Unparsable inputs. Try Again.");
                listplatforms = console.readLine("Enter Comma separated platforms to list:");
            }

            // Write the configuration to the .getMOSPatch.cfg file
            try {
                PrintWriter writer = new PrintWriter(".getMOSPatch.cfg", "UTF-8");
                for (String r: listplatforms.split(",")) {
                    ConfiguredPlatforms.put(r, platforms.get(r));
                    writer.println(r + ";" + platforms.get(r));
                }
                writer.close();
            } catch (Exception e) {
                throw e;
            }
            // if the config file existed, simply read the inputs from it.
        } else {
            for (String r: getMOSPatchcfg.split("\\r?\\n")) {
                ConfiguredPlatforms.put(r.split(";")[0], r.split(";")[1]);
            }
        }
        // Output the configured platforms.
        System.out.println("\nWe're going to download patches for the following Platforms/Languages:");
        for (Map.Entry < String, String > entry: ConfiguredPlatforms.entrySet()) {
            System.out.println(" " + entry.getKey() + " - " + entry.getValue());
        }
    }

    // this method prepares the list of file download URLs
    private static void BuildDLFileList(String patch, String regx) throws Exception {
        try {
            String patchDetails = "", DLPatchHTML = "", DLPatchHTML2 = "", PatchSelector = "";
            boolean pprotected;
            Pattern regex2;
            Matcher regexMatcher2;

            // Temporary variables are reset
            PatchFileList.clear();
            int PatchFileListCounter = 0;
            // Iterate through the list of platforms and languages
            for (Map.Entry < String, String > platform: ConfiguredPlatforms.entrySet()) {
                // keeps the password protection status
                pprotected = false;
                System.out.println();
                System.out.println("Processing patch " + patch + " for " + platform.getValue() + " and applying regexp " + regx + " to the filenames:");

                // Submit the patch+platform combination using the SimpleSearch form in MOS and read it into variable DLPatchHTML
                DLPatchHTML = DownloadString("https://updates.oracle.com/Orion/SimpleSearch/process_form?search_type=patch&patch_number=" + patch + "&plat_lang=" + platform.getKey());

                // Look for file download URL pattern in the retrieved HTML and collect it in the PatchFileList
                Pattern regex = Pattern.compile("https://.+?Download/process_form/[^\"]*.zip[^\"]*");
                Matcher regexMatcher = regex.matcher(DLPatchHTML);
                while (regexMatcher.find()) {
                    for (String oneline: regexMatcher.group(0).split("\\r?\\n")) {
                        if (oneline.split("process_form/")[1].split(".zip")[0].matches(regx)) {
                            PatchFileList.put(++PatchFileListCounter, oneline);
                        }
                    }
                }
                // Set the flag if password protected files were detected
                if (DLPatchHTML.contains("Download Password Protected Patch")) {
                    pprotected = true;
                }

                // Processing Multipart patches, i.e. 12978712
                // Basically we find the URL for the "Patch Details" where URLs of individual files are found.
                // Procesing is the same as above.
                regex = Pattern.compile("javascript:showDetails.\"/Orion/PatchDetails/process_form.+?Download Multi Part Patch");
                regexMatcher = regex.matcher(DLPatchHTML);
                while (regexMatcher.find()) {
                    for (String oneline: regexMatcher.group(0).split("\\r?\\n")) {
                        // Download the patch detail page
                        DLPatchHTML2 = DownloadString("https://updates.oracle.com" + oneline.split("\"")[1]);

                        // Look for file download URL pattern in the retrieved HTML and collect it in the PatchFileList
                        regex2 = Pattern.compile("https://.+?Download/process_form/[^\"]*.zip[^\"]*");
                        regexMatcher2 = regex2.matcher(DLPatchHTML2);
                        while (regexMatcher2.find()) {
                            for (String oneline2: regexMatcher2.group(0).split("\\r?\\n")) {
                                if (oneline2.split("process_form/")[1].split(".zip")[0].matches(regx)) {
                                    PatchFileList.put(++PatchFileListCounter, oneline2);
                                }
                            }
                        }
                        // Again check if anything's password protected
                        if (DLPatchHTML2.contains("Download Password Protected Patch")) {
                            pprotected = true;
                        }
                    }
                }
                // Display a warning if there are password protected files
                if (pprotected) {
                    System.out.println(" ! This patch contains password protected files (not listed). Use My Oracle Support to download them!");
                    // Display a message if no files were found
                } else if (PatchFileList.isEmpty()) {
                    System.out.println(" No files available");
                }
                //Produce the list of found files if anything was found
                for (Map.Entry < Integer, String > dlurl: PatchFileList.entrySet()) {
                    System.out.println(" " + dlurl.getKey() + " - " + dlurl.getValue().split("process_form/")[1].split(".zip")[0] + ".zip");
                }
                PatchSelector = "";
                // if parameter "download=all" was specified, don't ask for inputs, but download all files. This is especially useful in combination with "regexp" parameter
                if (parameters.containsKey("download") && parameters.get("download").equals("all") && !PatchFileList.isEmpty()) {
                    // downlaod all files here
                    System.out.println(" Enter Comma separated files to download: all");
                    System.out.println(" All files will be downloadad because download=all was specified.");
                    PatchSelector = "all";
                } else if (PatchFileList.isEmpty()) {
                    // Nothing needs to be done
                    //Ask for inputs and validate them
                } else {
                    Console console = System.console();
                    PatchSelector = console.readLine(" Enter Comma separated files to download: ");
                    while (!CheckInputsTree(PatchSelector, PatchFileList)) {
                        System.out.println("  ERROR: Unparsable inputs. Try Again.");
                        PatchSelector = console.readLine(" Enter Comma separated files to download: ");
                    }
                }
                // if "all" patches need to be downloaded - put them all in the DownloadFiles Map
                if (PatchSelector.equals("all")) {
                    for (Map.Entry < Integer, String > dlurl: PatchFileList.entrySet()) {
                        DownloadFiles.put(++DownloadFilesCounter, dlurl.getValue());
                    }
                } else if (PatchSelector.equals("")) {
                    // Nothing needs to be done
                    // Otherwise put only the chosen ones in the DownloadFiles Map
                } else {
                    for (String p: PatchSelector.split(",")) {
                        DownloadFiles.put(++DownloadFilesCounter, PatchFileList.get(Integer.parseInt(p)));
                    }
                }
            }

        } catch (Exception e) {
            throw e;
        }
    }

    // Method to download all files from URLs in DownloadFiles Map
    private static void DownloadAllFIles() throws Exception {
        try {
            System.out.println();
            if (!PatchFileList.isEmpty()) {
                System.out.println("Downloading all selected files:");
                //iterate through the URLs in the TreeMap
                for (Map.Entry < Integer, String > d: DownloadFiles.entrySet()) {
                    System.out.print(" ");
                    DownloadFile(d.getValue(), d.getValue().split("process_form/")[1].split(".zip")[0] + ".zip");
                }
            } else {
                System.out.println("There's Nothing to download!");
            }
        } catch (Exception e) {
            throw e;
        }
    }

    public static void main(String[] args) {
        try {

            //Populate the parameters map
            parameters = new HashMap < String, String > ();
            parameters.put("regexp", ".*");
            if (args.length > 0) {
            //will only consider parameters that contain "=", the rest is ignored
                for (String s: args) {
                    if (s.contains("=")) {
                        parameters.put(s.split("=")[0], s.split("=")[1]);
                    }
                }

                // Setting the Cookie handling and the Authenticator
                CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
                Authenticator.setDefault(new CustomAuthenticator());

                //Logs on to MOS, and initiates the Authenticator and the SSL session
                String waste = DownloadString("https://updates.oracle.com/Orion/SimpleSearch/switch_to_saved_searches");

                // Initiate platforms list to download the patches for
                if (parameters.containsKey("patch") || (parameters.containsKey("reset") && parameters.get("reset").equals("yes"))) {
                    Platforms();
                }

                // Iterate through the requested patches and download them one by one
                if (parameters.containsKey("patch")) {
                    for (String p: parameters.get("patch").split(",")) {
                        BuildDLFileList(p, parameters.get("regexp"));
                    }
                    //Download all files
                    DownloadAllFIles();
                    // Output if no patches were chosen
                } else {
                    System.out.println("\nNo patch numbers are specified.");
                }
            } else {
                System.out.println("\nERROR: At least one parameter needs to be specified!");
                System.out.println("USAGE: java -jar getMOSPatch.jar patch=<patch_number_1>[,<patch_number_n>]* [platform=<plcode_1>[,<plcode_n>]*] [reset=yes] [regexp=<regular_expression>] [download=all] [MOSUser=<username>] [MOSPass=<password>]");
            }
            // Just a generic exception display
            // This is not very sophisticated, but should be good enough
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
