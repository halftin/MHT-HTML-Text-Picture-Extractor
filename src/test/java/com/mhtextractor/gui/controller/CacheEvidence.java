package com.mhtextractor.gui.controller;
import java.nio.file.*;
import java.util.List;
/** Reads Q09's backend-owned root; it never deletes another process's cache. */
final class CacheEvidence {
    private final Path root;
    CacheEvidence(String root){this.root=Path.of(root);}
    CacheEvidence store(){return this;}
    Path root(){return root;}
    List<String> listKeys(){
        if(!Files.isDirectory(root))return List.of();
        try(var entries=Files.list(root)){return entries.filter(Files::isDirectory).filter(p->Files.exists(p.resolve(".complete"))).map(p->p.getFileName().toString()).toList();}
        catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    int deleteAll(){if(!listKeys().isEmpty())throw new AssertionError("New backend session must begin with an empty cache");return 0;}
}
