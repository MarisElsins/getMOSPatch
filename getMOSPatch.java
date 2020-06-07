/*
File name:          getMOSPatch.java
Version:            2.3
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

Changes:
        2.0: Maris - Complete rewrite to Java
        2.1: Maris - Adjustments to the new user authentication process Oracle implemented (13-May-2020)
        2.2: Maris - the platforms list won't be downloaded anymore when the "platform" parameter is specified; Cleanup of the code
        2.3: Timur - code refactoring; stop reading patch search page for performance; debug

Usage:
        java -jar getMOSPatch.jar patch=<patch_number_1>[,<patch_number_n>]* \
                                  [reset=yes] \
                                  [platform=<plcode_1>[,<plcode_n>]*] \
                                  [regexp=<regular_expression>] \
                                  [download=all] \
                                  [stagedir=<directory path>] \
                                  [MOSUser=<username>] \
                                  [MOSPass=<password>] \
                                  [silent=yes]

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
                    stagedir -      Optionally specify the staging directory. The current directory is the default.
                    MOSUser -       Optionally specify the MOS username, if not provided, it will be prompted.
                    MOSPass -       Optionally specify the MOS pasword, if not provided, it will be prompted.
                    silent=yes -    The dynamic progress indicator is not displayed.

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

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class getMOSPatch {
    //Constants section
    private static final int BUFFER_SIZE = 128*1024;
    private static final int PROGRESS_INTERVAL = 1024 * 1024;
    private static final int LIMIT_PAGE_SIZE = 256 * 1024;

    private static final char[] pchar = {
        '-', '\\', '|', '/'
    };

    // I'll store the passed parameters in this Map
    private static Map<String, String> parameters;

    // holds the list of configured Platforms/Languages
    private static Map<String, String> configuredPlatforms = new HashMap<String, String>();

    // DownloadFiles contains URLs to download
    private static Map<Integer, String> downloadFiles = new TreeMap<Integer, String>();
    private static int downloadFilesCounter = 0;

    // Intermediate MAP that populates the URLs for specific patch for the time inputs are collected.
    private static Map<Integer, String> patchFileList = new TreeMap<Integer, String>();

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

    private static boolean checkParam(String key, String value) {
        return parameters.containsKey(key) && value.equals(parameters.get(key));
    }

    // method to download a file
    private static void downloadFile(String url, String filename) throws IOException {
        downloadFile(url, filename, Long.MAX_VALUE);
    }

    private static void downloadFile(String url, String filename, long limit) throws IOException {
        long fileSize = 0;
        int printSize = 0;
        long tim1, tim2;
        String progressData = " ";

        BufferedInputStream in = new BufferedInputStream(new URL(url).openStream());
        FileOutputStream outputStream = new FileOutputStream(filename);
        int bytesRead = -1;
        int iterator = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        // I'm using this hardcoded filename from webpage download.
        if (!filename.equals(".getMOSPatch.tmp")) {
            System.out.print("Downloading " + filename + ":  ");
        }
        // The download is happening here. I've pimped it with some progress display (except when downloading a webpage)
        tim1 = System.currentTimeMillis();
        while ((bytesRead = in.read(buffer, 0, BUFFER_SIZE)) != -1 && fileSize <= limit) {
            fileSize = fileSize + (long) bytesRead;
            outputStream.write(buffer, 0, bytesRead);
            // Show extended download progress only if downloading a real file
            // I've seen this stuff sometimes not working on windows.
            boolean silent = checkParam("silent", "yes");
            if (!filename.equals(".getMOSPatch.tmp")) {
                if ((printSize + PROGRESS_INTERVAL < fileSize) && !silent) {
                    tim2 = System.currentTimeMillis();
                    System.out.print(
                            String.format("%" + progressData.length() + "s", "")
                                .replace(" ", "\b")
                    );
                    progressData = pchar[(iterator++ % 4)] + " " + fileSize / 1024 / 1024 + "MB" +
                            " at average speed of " + fileSize / (tim2 - tim1) + "KB/s        ";
                    System.out.print(progressData);
                    printSize = printSize + PROGRESS_INTERVAL;
                }
                // If downloading a webpage, just show a rotating char as a sign that something's ongoing, unless silent=yes
            } else if (!silent) {
                System.out.print(
                        String.format("%" + progressData.length() + "s", "")
                            .replace(" ", "\b") + pchar[(iterator++ % 4)]
                );
            }
        }
        outputStream.flush(); outputStream.close(); in.close();
        // Download completed. In case of a real file post the final stats, otherwise remove the char.
        System.out.print(String.format("%" + progressData.length() + "s", "").replace(' ', '\b'));
        if (!filename.equals(".getMOSPatch.tmp")) {
            tim2 = System.currentTimeMillis();
            if (tim2 == tim1) { tim2++; }
            progressData = fileSize / 1024 / 1024 + "MB at average speed of " + fileSize / (tim2 - tim1) + "KB/s - DONE!";
            System.out.println(progressData);
        } else {
            System.out.print("\b");
        }
    }

    // reads a file and returns a string
    private static String readFile(String fileName) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(fileName));
        StringBuilder sb = new StringBuilder();
        String line = br.readLine();
        while (line != null) {
            sb.append(line).append("\n");
            line = br.readLine();
        }
        br.close();
        return sb.toString();
    }

    // downloads from URL into a String
    private static String downloadString(String url) throws IOException {
        return downloadString(url, Long.MAX_VALUE);
    }

    private static String downloadString(String url, long limit) throws IOException {
        downloadFile(url, ".getMOSPatch.tmp", limit);
        String outputString = readFile(".getMOSPatch.tmp");
        boolean result = new File(".getMOSPatch.tmp").delete();
        if (!result)
            System.out.println("warning: can't remove .getMOSPatch.tmp");
        return outputString;
    }

    // Validates that all values in the passed comma separated string exists in the Map<String, String>
    private static boolean checkInputs(String inputs, Map<String, String> map) {
        for (String p: inputs.split(",")) {
            if (!map.containsKey(p))
                return false;
        }
        return true;
    }

    // Validates that all values in the passed comma separated string exists in the Map<Integer, String>
    private static boolean checkInputsTree(String inputs, Map<Integer, String> map) {
        // special processing for "all", as it's processed later
        if (inputs.equals("") || inputs.equals("all")) {
            return true;
        }
        for (String p: inputs.split(",")) {
            try {
                if (!map.containsKey(Integer.parseInt(p)))
                    return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    // Method to populate platforms
    private static void platforms() throws IOException {
        String getMOSPatchcfg = "";
        String listPlatforms = "";
        String s;
        // this map is used to store platform/language codes and description from MOS.
        Map<String, String> platforms = new HashMap<String, String>();

        // Reading the .getMOSPatch.cfg file into getMOSPatchcfg variable if the file exists
        try {
            getMOSPatchcfg = readFile(".getMOSPatch.cfg");
        } catch (IOException e) {
        }

        // Populate variables with available platforms
        // Checking if we need to download platform list from MOS, yes if:
        //  * parameter "reset=yes" was passed, or
        //  * "platforms" parameter was provided (we need to validate the input), or
        //  * the .getMOSPatch.cfg was empty or didn't exist
        if (checkParam("reset", "yes") || parameters.containsKey("platform") || "".equals(getMOSPatchcfg)) {
            if (parameters.get("platform") == null) {
                System.out.println("Platforms and languages need to be reset.");
                System.out.println("Obtaining the list of platforms and languages:");

                // download the search page into variable s
                s = downloadString("https://updates.oracle.com/Orion/SavedSearches/switch_to_simple", LIMIT_PAGE_SIZE);
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
                    listPlatforms = console.readLine("Enter Comma separated platforms to list: ");
                } else {
                    listPlatforms = parameters.get("platform");
                    System.out.println("Enter Comma separated platforms to list: " + listPlatforms);
                    parameters.remove("platform");
                }
                // check the inputs as many times as necessary
                while (!checkInputs(listPlatforms, platforms)) {
                    System.out.println(" ERROR: Unparseable inputs. Try Again.");
                    listPlatforms = console.readLine("Enter Comma separated platforms to list:");
                }

                // Write the configuration to the .getMOSPatch.cfg file
                PrintWriter writer = new PrintWriter(".getMOSPatch.cfg", "UTF-8");
                for (String r : listPlatforms.split(",")) {
                    configuredPlatforms.put(r, platforms.get(r));
                    writer.println(r + ";" + platforms.get(r));
                }
                writer.flush(); writer.close();
            } else {
                for (String r: parameters.get("platform").split(",")) {
                    configuredPlatforms.put(r, "Platform " + r);
                }
            }
            // if the config file existed, simply read the inputs from it.
        } else {
            for (String r: getMOSPatchcfg.split("\\r?\\n")) {
                configuredPlatforms.put(r.split(";")[0], r.split(";")[1]);
            }
        }
        // Output the configured platforms.
        System.out.println("\nWe're going to download patches for the following Platforms/Languages:");
        for (Map.Entry < String, String > entry: configuredPlatforms.entrySet()) {
            System.out.println(" " + entry.getKey() + " - " + entry.getValue());
        }
    }

    // this method prepares the list of file download URLs
    private static void buildDLFileList(String patch, String regx) throws IOException {
        String dlPatchHTML = "", dlPatchHTML2 = "", patchSelector = "";
        boolean pwdProtected;
        Pattern regex2;
        Matcher regexMatcher2;

        // Temporary variables are reset
        int patchFileListCounter = 0;
        // Iterate through the list of platforms and languages
        for (Map.Entry <String, String> platform: configuredPlatforms.entrySet()) {
            // keeps the password protection status
            pwdProtected = false;
            patchFileList.clear();
            patchFileListCounter = 0;
            System.out.println("\nProcessing patch " + patch + " for " + platform.getValue() + " and applying regexp " + regx + " to the filenames:");

            // Submit the patch+platform combination using the SimpleSearch form in MOS and read it into variable dlPatchHTML
            dlPatchHTML = downloadString(
                    "https://updates.oracle.com/Orion/SimpleSearch/process_form?search_type=patch&patch_number=" + patch + "&plat_lang=" + platform.getKey(),
                    LIMIT_PAGE_SIZE);

            // Look for file download URL pattern in the retrieved HTML and collect it in the PatchFileList
            Pattern regex = Pattern.compile("https://.+?Download/process_form/[^\"]*.zip[^\"]*");
            Matcher regexMatcher = regex.matcher(dlPatchHTML);
            while (regexMatcher.find()) {
                for (String oneline: regexMatcher.group(0).split("\\r?\\n")) {
                    if (oneline.split("process_form/")[1].split(".zip")[0].matches(regx)) {
                        patchFileList.put(++patchFileListCounter, oneline);
                    }
                }
            }
            // Set the flag if password protected files were detected
            if (dlPatchHTML.contains("Download Password Protected Patch")) {
                pwdProtected = true;
            }

            // Processing Multipart patches, i.e. 12978712
            // Basically we find the URL for the "Patch Details" where URLs of individual files are found.
            // Procesing is the same as above.
            regex = Pattern.compile("javascript:showDetails.\"/Orion/PatchDetails/process_form.+?Download Multi Part Patch");
            regexMatcher = regex.matcher(dlPatchHTML);
            while (regexMatcher.find()) {
                for (String oneline: regexMatcher.group(0).split("\\r?\\n")) {
                    // Download the patch detail page
                    dlPatchHTML2 = downloadString("https://updates.oracle.com" + oneline.split("\"")[1]);

                    // Look for file download URL pattern in the retrieved HTML and collect it in the PatchFileList
                    regex2 = Pattern.compile("https://.+?Download/process_form/[^\"]*.zip[^\"]*");
                    regexMatcher2 = regex2.matcher(dlPatchHTML2);
                    while (regexMatcher2.find()) {
                        for (String oneline2: regexMatcher2.group(0).split("\\r?\\n")) {
                            if (oneline2.split("process_form/")[1].split(".zip")[0].matches(regx)) {
                                patchFileList.put(++patchFileListCounter, oneline2);
                            }
                        }
                    }
                    // Again check if anything's password protected
                    if (dlPatchHTML2.contains("Download Password Protected Patch")) {
                        pwdProtected = true;
                    }
                }
            }
            // Display a warning if there are password protected files
            if (pwdProtected) {
                System.out.println(" ! This patch contains password protected files (not listed). Use My Oracle Support to download them!");
                // Display a message if no files were found
            } else if (patchFileList.isEmpty()) {
                System.out.println(" No files available");
            }
            //Produce the list of found files if anything was found
            for (Map.Entry < Integer, String > dlurl: patchFileList.entrySet()) {
                System.out.println(" " + dlurl.getKey() + " - " + dlurl.getValue().split("process_form/")[1].split(".zip")[0] + ".zip");
            }
            patchSelector = "";
            // if parameter "download=all" was specified, don't ask for inputs, but download all files. This is especially useful in combination with "regexp" parameter
            if (checkParam("download", "all") && !patchFileList.isEmpty()) {
                // download all files here
                System.out.println(" Enter Comma separated files to download: all");
                System.out.println(" All files will be downloaded because download=all was specified.");
                patchSelector = "all";
            } else if (patchFileList.isEmpty()) {
                // Nothing needs to be done
                //Ask for inputs and validate them
            } else {
                Console console = System.console();
                patchSelector = console.readLine(" Enter Comma separated files to download: ");
                while (!checkInputsTree(patchSelector, patchFileList)) {
                    System.out.println("  ERROR: Unparsable inputs. Try Again.");
                    patchSelector = console.readLine(" Enter Comma separated files to download: ");
                }
            }
            // if "all" patches need to be downloaded - put them all in the DownloadFiles Map
            if (patchSelector.equals("all")) {
                for (Map.Entry<Integer, String> dlurl: patchFileList.entrySet()) {
                    downloadFiles.put(++downloadFilesCounter, dlurl.getValue());
                }
            } else if (patchSelector.equals("")) {
                // Nothing needs to be done
                // Otherwise put only the chosen ones in the DownloadFiles Map
            } else {
                for (String p: patchSelector.split(",")) {
                    downloadFiles.put(++downloadFilesCounter, patchFileList.get(Integer.parseInt(p)));
                }
            }
        }
    }

    // Method to download all files from URLs in DownloadFiles Map
    private static void downloadAllFiles() throws IOException {
        String targetDir = "";
        String stageDir = parameters.containsKey("stagedir") ? parameters.get("stagedir") : "";
        if (!"".equals(stageDir)) {
            targetDir = stageDir + File.separator;
        }
        System.out.println();
        if (!downloadFiles.isEmpty()) {
            System.out.println("Downloading all selected files:");
            //iterate through the URLs in the TreeMap
            for (Map.Entry <Integer, String> d: downloadFiles.entrySet()) {
                System.out.print(" ");
                downloadFile(d.getValue(), targetDir + d.getValue().split("process_form/")[1].split(".zip")[0] + ".zip");
            }
        } else {
            System.out.println("There's nothing to download!");
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 0) {
            //Populate the parameters map
            parameters = new HashMap<String, String>();
            parameters.put("regexp", ".*");
            TreeMap<String, Long> debug = new TreeMap<String, Long>();
            long t1 = System.currentTimeMillis();

            //will only consider parameters that contain "=", the rest is ignored
            for (String s: args) {
                if (s.contains("=")) {
                    parameters.put(s.split("=")[0], s.split("=")[1]);
                }
            }

            // Iterate through the requested patches and download them one by one
            if (parameters.containsKey("patch")) {
                // Setting the Cookie handling and the Authenticator
                CookieManager cookieMgr = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
                CookieHandler.setDefault(cookieMgr);
                Authenticator.setDefault(new CustomAuthenticator());

                //Logs on to MOS, and initiates the Authenticator and the SSL session
                String waste = downloadString("https://updates.oracle.com/Orion/Services/download");
                debug.put("1. set up", System.currentTimeMillis() - t1);

                t1 = System.currentTimeMillis();
                platforms();
                debug.put("2. get platforms", System.currentTimeMillis() - t1);

                t1 = System.currentTimeMillis();
                for (String p: parameters.get("patch").split(",")) {
                    if (!"".equals(p))
                        buildDLFileList(p, parameters.get("regexp"));
                }
                debug.put("3. build list", System.currentTimeMillis() - t1);

                //Download all files
                t1 = System.currentTimeMillis();
                downloadAllFiles();
                debug.put("4. download files", System.currentTimeMillis() - t1);

                if (checkParam("debug", "yes")) {
                    System.out.println("Timings (ms): ");
                    for (Map.Entry<String, Long> e: debug.entrySet()) {
                        System.out.printf(" %20s: %9d%n", e.getKey(), e.getValue());
                    }
                }
            } else {
                System.out.println("\nNo patch numbers are specified.");
            }
        } else {
            System.out.println("\nERROR: At least one parameter needs to be specified!");
            System.out.println("USAGE: java -jar getMOSPatch.jar patch=<patch_number_1>[,<patch_number_n>]* [platform=<plcode_1>[,<plcode_n>]*] [reset=yes] [regexp=<regular_expression>] [download=all] [MOSUser=<username>] [MOSPass=<password>]");
        }
    }
}