Health report is java agent that illustrate how the JFR Event Streaming API can be
used. 

The agent can be started like this:

  $ java -javaagent:health-report.jar MyApp

It will run alongside with the main applicationand print data producedd by JFR. 

<pre>
=================== HEALTH REPORT === 2019-05-16 23:57:50 ====================
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


