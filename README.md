THIS PROJECT IS NOT MAINTAINED ANYMORE

[![](https://travis-ci.com/NicoKiaru/elastix_registration_server.svg?branch=master)](https://travis-ci.com/NicoKiaru/elastix_registration_server)

# Elastix Registration Server
Java-based [elastix](https://github.com/SuperElastix/elastix) registration server. This server can process Elastix and Transformix jobs.
It is not aimed to be used as a standalone server because the way the tasks are processed and queued 
is not straightforward. 

It is aimed to be used from within ImageJ/Fiji in combination with the [`bigdataviewer-playground`](https://github.com/bigdataviewer/bigdataviewer-playground) 
update site or - that's the reason it has been developed, with the [ABBA](https://c4science.ch/w/bioimaging_and_optics_platform_biop/image-processing/image-to-atlas-registration/) plugin, a Fiji
plugin designed for brain slice registration to the Allen Brain Atlas.
