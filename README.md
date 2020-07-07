# getMOSPatch

getMOSPatch V2 - A Tool that helps downloading patches from My Oracle Support directly to the server! This version is completely rewritten in java and it should run on most platforms using JRE 1.6 or later.

* File name: getMOSPatch.java
* Version: 2.6
* Purpose: An easier way to download patches from [My Oracle Support (MOS)](https://support.oracle.com). All you need is: Valid MOS credentials, the patch number(s), list of target platform/languages that the patch is required for, internet connectivity and JRE 1.6 or higher
* Author: Maris Elsins (elmaris at gmail.com)
* Copyright: (c) Maris Elsins - [https://me-dba.com](https://me-dba.com) - All rights reserved.
* Disclaimer:  This script is provided "as is", so no warranties or guarantees are made about its correctness, reliability and safety. Use it at your own risk!

## License

1. You may use this script for your (or your businesses) purposes for free
1. You may modify this script as you like for your own (or your businesses) purpose, but you must always leave this script header (the entire comment section), including the author, copyright and license sections as the first thing in the beginning of this file
1. You may NOT publish or distribute this script, java classes compiled from it, or packaged jar files, or any other variation of it PUBLICLY (including, but not limited to uploading it to your public website or ftp server), instead just link to its location in [https://github.com/MarisElsins/getMOSPatch](https://github.com/MarisElsins/getMOSPatch)
1. You may distribute this script INTERNALLY in your company, for internal use only, for example when building a standard DBA toolset to be deployed to all servers or DBA workstations

## Usage

Before you start, download [https://github.com/MarisElsins/getMOSPatch/raw/master/getMOSPatch.jar](https://github.com/MarisElsins/getMOSPatch/raw/master/getMOSPatch.jar) and then execute it like this:

```bash
java -jar getMOSPatch.jar patch=< patch_number_1>[,< patch_number_n>]* \
                          [reset=yes] \
                          [platform=<plcode_1>[,<plcode_n>]*] \
                          [regexp=<regular_expression>] \
                          [download=all] \
                          [stagedir=<target directory path>] \
                          [MOSUser=<username>] \
                          [MOSPass=<password>] \
                          [silent=yes] \
                          [debug=yes]
```

* Note 1: for JRE 1.6: use *java -Dhttps.protocols=TLSv1 -jar getMOSPatch.jar ...*
* Note 2: If you're behind a proxy, use the appropriate java flags -DsocksProxyHost=... and -DsocksProxyPort=... (for socks proxy) or -Dhttps.proxyHost=... and -Dhttps.proxyPort=... (For HTTPS proxy), for example `java -DsocksProxyHost=host_proxy -DsocksProxyPort=8888 -jar getMOSPatch.jar patch=6880880`
* Note 3: Usage notes are provided for a packaged jre
* Note 4: Order of parameters is irrelevant

Explanation of parameters:

* patch - list of patches to download, i.e. 6880880,16867777,12978712
* reset=yes - This will initiate the resetting of the chosen Platforms/Languages, otherwise the list previous time used is retrieved from .getMOSPatch.cfg
* platform - List of comma separated platform language codes The code list is presented the first time you execute the script. I.e. "226P,3L" for Linux x86-64 and Canadian French (FRC)
* regexp - regular expression to filter the filenames. Typically this can be used if the same patch is available for multiple releases of software and you know which one you need. I.e. .*121.* would be useful for Oracle Database 12c (R1)
* download=all - specify to download all found files without need to specify inputs. Very useful when "regexp" parameter is used
* stagedir - Optionally specify the target directory path for the downloaded patches.
* MOSUser - Optionally specify the MOS username, if not provided, it will be prompted.
* MOSPass - Optionally specify the MOS pasword, if not provided, it will be prompted.
* silent=yes - The dynamic progress indicator is not displayed.
* debug=yes - Outputs the timings of different steps

## Example:  To download OPatch for 11gR2 database on Linux x86-64

```bash
$ java -jar getMOSPatch.jar MOSUser=elsins@nomail.com patch=6880880 regexp=.*1120.* download=all
Enter your MOS password:

We're going to download patches for the following Platforms/Languages:
 226P - Linux x86-64

Processing patch 6880880 for Linux x86-64 and applying regexp .*1120.* to the filenames:
 1 - p6880880_112000_Linux-x86-64.zip
 Enter Comma separated files to download: all
 All files will be downloadad becuase download=all was specified.

Downloading all selected files
 Downloading p6880880_112000_Linux-x86-64.zip: 50MB at average speed of 3116KB/s - DONE!
```

## Build instructions (by example)

This jar is built on OS X 10.12.3.
The rt.jar used for "-bootclasspath" was obtained from the Linux x64 version of jre 6u45 available [here](http://www.oracle.com/technetwork/java/javase/downloads/java-archive-downloads-javase6-419409.html#jre-6u45-oth-JPR):

```bash
$ javac -version
javac 1.8.0_66

$ rm getMOSPatch.jar; javac -bootclasspath /tmp/jdk1.6.0_45/jre/lib/rt.jar -source 1.6 -target 1.6 getMOSPatch.java && jar cvmf META-INF/MANIFEST.MF getMOSPatch.jar getMOSPatch*.class; rm *.class
added manifest
adding: getMOSPatch.class(in = 14113) (out= 7419)(deflated 47%)
```
