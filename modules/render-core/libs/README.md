# Caliko

`caliko-1.3.8.jar` is the unmodified core library from the official
[Caliko v1.3.8 release](https://github.com/FedUni/caliko/releases/tag/v1.3.8),
entry `caliko/jar/caliko-1.3.8.jar` of `caliko-distribution-v1.3.8.zip`.
It has no external runtime dependencies. The visualization and demo libraries are excluded.

SHA-256: `e4fa91f5a150d4ffbce9e81b3e4059b88402942873af1fe17060febb1e58fc22`.
License: [MIT](caliko-LICENSE.txt). Upstream source: https://github.com/FedUni/caliko/tree/v1.3.8/caliko

The core jar is embedded in the render-core artifact and shaded into the modelling artifact;
it is also present on ModDev's development classpath. No network download happens at runtime.
