The jar is built on Linux x86-64:

$ java -version
java version "1.6.0_75"
Java(TM) SE Runtime Environment (build 1.6.0_75-b13)
Java HotSpot(TM) 64-Bit Server VM (build 20.75-b01, mixed mode)

$ rm getMOSPatch.jar; javac getMOSPatch.java && jar cvmf META-INF/MANIFEST.MF getMOSPatch.jar getMOSPatch*.class; rm *.class
added manifest
adding: getMOSPatch$1.class(in = 184) (out= 149)(deflated 19%)
adding: getMOSPatch.class(in = 11903) (out= 6194)(deflated 47%)
adding: getMOSPatch$CustomAuthenticator.class(in = 1347) (out= 724)(deflated 46%)
adding: getMOSPatch$ReadFile.class(in = 1022) (out= 665)(deflated 34%)
