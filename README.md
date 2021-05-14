# Description

Health Report is a Java program that demonstrates the JFR Event Streaming API.

It's both a Java agent and a .java file that can be launched as a single-file program. The agent runs alongside with an ordinary Java application and prints data produced by JFR to standard out. 

Health report requires JDK 16, or later, and only works with OpenJDK/Oracle JDK.

For a video demonstration see:

https://youtu.be/E9K5m1HXMSc?t=367

Example output:

<pre>
=================== HEALTH REPORT === 2021-05-13 23:57:50 ====================
| GC: G1Old/G1New         Phys. memory: 28669 MB    Alloc Rate: 8 MB/s       |
| OC Count    : 28        Initial Heap: 448 MB      Total Alloc: 190 MB      |
| OC Pause Avg: 40.1 ms   Used Heap   : 19 MB       Thread Count: 20.0       |
| OC Pause Max: 48.8 ms   Commit. Heap: 47 MB       Class Count : 3894.0     |
| YC Count    : 8         CPU Machine   :  20.12 %  Safepoints: 335          |
| YC Pause Avg: 5.7 ms    CPU JVM User  :  10.28 %  Max Safepoint: 46.4 ms   |
| YC Pause Max: 22.4 ms   CPU JVM System:   1.07 %  Max Comp. Time: 728.3 ms |
|--- Top Allocation Methods ------------------------------- -----------------|
| DataBufferInt.<init>(int)                                                11.27 % |
| Component.size()                                                    9.01 % |
| BufferedContext.validate(...)                                       6.21 % |
| Path2D$Double.<init>(...)                                                 5.87 % |
| SunGraphics2D.clone()                                               5.85 % |
|--- Hot Methods ------------------------------------------------------------|
| DRenderer._endRendering(int, int)                                  51.11 % |
| DRenderer.copyAARow(...)                                            6.67 % |
| Arrays.fill(...)                                                    4.44 % |
| StringConcatFactory.doStringConcat(...)                             2.22 % |
| MarlinTileGenerator.getAlphaNoRLE(...)                              2.22 % |
==============================================================================
</pre>

# Build Instructions

    $ cd src
    $ javac HealthReport.java
    $ jar cmf META-INF/MANIFEST.MF health-report.jar .

# Getting Started

To run Health Report as a Java agent:

    $ java -javaagent:health-report.jar com.example.MyApp

To run Health Report as a single-file program:
  
    $ java HealthReport.java <source>

The source is where events are streamed from. It can be a Java process, repository directory, network address, recording file, or itself.
 
    $ java HealthReport.java MyApplication
    $ java HealthReport.java --debug MyApplication
    $ java HealthReport.java --scroll MyApplication
    $ java HealthReport.java --timeout 5 MyApplication
    $ java HealthReport.java com.example.MyApplication
    $ java HealthReport.java example.module/com.example.MyApplication
    $ java HealthReport.java application.jar
    $ java HealthReport.java /programs/application.jar
    $ java HealthReport.java 4711
    $ java HealthReport.java /repository/
    $ java HealthReport.java /repository/2021_03_30_09_48_31_60185
    $ java HealthReport.java example.com:7091
    $ java HealthReport.java 127.0.0.1:7092
    $ java HealthReport.java [0:0:0:0:0:0:0:1]:7093
    $ java HealthReport.java recording.jfr
    $ java HealthReport.java /directory/perf.jfr
    $ java HealthReport.java --replay-speed 10 recording.jfr
    $ java HealthReport.java self

If the source is a network address running a JMX management agent, Health Report starts a recording on the host. Otherwise, it must be started manually, for example using -XX:StartFlightRecording.

If a stream can't be created, for example, if the source process hasn't started yet, Health Report will retry until the source becomes available.

For more detailed information see [usage](https://github.com/flight-recorder/health-report/blob/master/Usage.md).

# Known issues

##### The "Top Allocation Methods" table is empty

In JDK 16, a new allocation event with less overhead was introduced (jdk.ObjectAllocationSample). This means the table will be empty for earlier release where the event is not available.

##### The timestamp is missing

The displayed timestamp at the top of Health Report comes from jdk.Flush event which is not enabled when using -XX:StartFlightRecording. If Health Report is running as a Java agent, or it connects over JMX, it is automatically enabled. It is not possible to turn the event on when streaming from a directory or a file. 