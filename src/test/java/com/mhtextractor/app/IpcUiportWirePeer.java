package com.mhtextractor.app;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mhtextractor.backend.BackendIdentity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Deliberately invalid or adversarial child process, never a production protocol hook. */
public final class IpcUiportWirePeer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static long revision;
    private static ObjectNode frame(String type) { return JSON.createObjectNode().put("v",1).put("type",type); }
    private static void emit(String kind, ObjectNode data) {
        data.put("revision",++revision);
        System.out.println(frame("event").put("event",kind).set("data",data));
    }
    private static ObjectNode ref(Path path) throws Exception {
        return JSON.createObjectNode().put("path",path.toString()).put("bytes",Files.size(path)).put("mediaType","application/json");
    }
    private static ObjectNode workspace(Path root) throws Exception {
        Path order=Files.createTempFile(root,"order-",".json"); Files.writeString(order,"[]");
        var snapshot=JSON.createObjectNode().put("canUndo",false).put("undoDepth",0).put("selection","NONE").put("revision",revision);
        snapshot.putArray("groups"); snapshot.putArray("topLevelItems"); snapshot.set("topLevelOrder",ref(order));
        Path manifest=Files.createTempFile(root,"manifest-",".json"); Files.writeString(manifest,snapshot.toString());
        return JSON.createObjectNode().put("revision",revision).set("manifest",ref(manifest));
    }
    private static void question(String id, long timeout) {
        var data = JSON.createObjectNode().put("confirmationId",id).put("key","confirm.test").putNull("defaultAction").put("timeoutMs",timeout);
        data.putObject("namedParams").put("id",id); data.putArray("actionKeys").add("continue");
        emit("confirmationRequested",data);
    }
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        Path root = Files.createTempDirectory("ipc-client-peer-");
        var ready = frame("event").put("event","ready"); ready.putObject("data").putArray("methods").add("hello");
        System.out.println(ready);
        input.readLine();
        var identity = BackendIdentity.current();
        var welcome = frame("response").put("id","launcher-hello").put("revision",0);
        welcome.putObject("result").put("kind","welcome").put("protocolVersion",identity.protocolVersion())
                .put("version",identity.version()).put("buildId",identity.buildId()).put("confirmationRoundTrip",true)
                .putArray("capabilities").add("workspace").add("setChecked").add("workspacePage").add("bulkFiles").add("confirmationRoundTrip");
        System.out.println(welcome);
        String line;
        boolean fired = false;
        boolean bulkBusy = false;
        var deferredReleases = new java.util.ArrayList<JsonNode>();
        JsonNode deferredWorkspace = null;
        while ((line = input.readLine()) != null) {
            JsonNode request = JSON.readTree(line); String method = request.path("method").asText();
            if (method.equals("releaseFiles") && bulkBusy) {
                if (deferredReleases.size() >= 256) {
                    var error = frame("error").put("id",request.path("id").asText());
                    error.putObject("error").put("code","INVALID_REQUEST").put("message","Busy request queue is full: release queue overflow");
                    System.out.println(error);
                } else deferredReleases.add(request);
                continue;
            }
            if(method.equals("workspace") && bulkBusy) { deferredWorkspace=request; continue; }
            JsonNode result = JSON.nullNode();
            if (method.equals("workspace")) {
                result = workspace(root);
            } else if (method.equals("fileInfo")) {
                result = ref(Path.of(request.path("params").path("path").asText()));
            } else if (method.equals("releaseFiles")) {
                for (var path : request.path("params").path("paths")) Files.deleteIfExists(Path.of(path.asText()));
            } else if (method.equals("confirmAnswer")) {
                Files.writeString(root.resolve(request.path("params").path("confirmationId").asText()+".answer"),
                        request.path("params").path("actionKey").toString());
                result = JSON.createObjectNode().put("accepted",true);
                bulkBusy = false;
            } else if (!fired) {
                fired = true;
                switch (args[0]) {
                    case "unknown" -> emit("inventedBusinessKind",JSON.createObjectNode());
                    case "regress" -> {
                        emit("workspaceActivityChanged",JSON.createObjectNode().put("generation",1));
                        revision = 0; emit("workspaceActivityChanged",JSON.createObjectNode().put("generation",2));
                    }
                    case "overflow" -> {
                        var data = JSON.createObjectNode().put("generation",1).put("revision",new java.math.BigInteger("9223372036854775808"));
                        System.out.println(frame("event").put("event","workspaceActivityChanged").set("data",data));
                    }
                    case "replace" -> { question("first",2000); question("second",2000); }
                    case "timeout" -> question("first",80);
                    case "bulk-busy" -> {
                        bulkBusy = true;
                        for(int i=0;i<300;i++) {
                            Path payload=Files.createTempFile(root,"parse-",".json");
                            var parsed=JSON.createObjectNode().put("path","source").put("status","READY").putNull("errorKey")
                                    .put("errorDetail","").put("text","content").put("textAssigned",true).putNull("title").put("titleAssigned",false);
                            Files.writeString(payload,parsed.toString());
                            emit("preparseFinished",JSON.createObjectNode().put("generation",42).set("result",ref(payload)));
                        }
                        question("busy-card",10000);
                    }
                    case "disconnect" -> { return; }
                    case "stall" -> { input.readLine(); return; }
                    case "reject" -> {
                        var error = frame("error").put("id",request.path("id").asText());
                        error.putObject("error").put("code","UNKNOWN_METHOD").put("message","Unsupported test method");
                        System.out.println(error); continue;
                    }
                }
            }
            var response = frame("response").put("id",request.path("id").asText()).put("revision",revision);
            response.set("result",result); System.out.println(response);
            if (!bulkBusy) {
                for(var deferredRelease:deferredReleases) {
                    for(var path:deferredRelease.path("params").path("paths")) Files.deleteIfExists(Path.of(path.asText()));
                    System.out.println(frame("response").put("id",deferredRelease.path("id").asText()).put("revision",revision).set("result",JSON.nullNode()));
                }
                deferredReleases.clear();
                if(deferredWorkspace!=null) {
                    System.out.println(frame("response").put("id",deferredWorkspace.path("id").asText()).put("revision",revision).set("result",workspace(root)));
                    deferredWorkspace=null;
                }
            }
        }
    }
}
