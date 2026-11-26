-keep class io.github.nitsuya.aa.display.** { *; }
# AARE AAP protocol stack (GPL-3.0-or-later, vendor/aare-aap) and the generated
# aap_protobuf messages: keep names. protobuf-lite relies on generated per-class
# metadata; obfuscating the protocol layer also makes AAP logs unreadable.
-keep class net.prototux.aare.** { *; }
-keep class aap_protobuf.** { *; }
