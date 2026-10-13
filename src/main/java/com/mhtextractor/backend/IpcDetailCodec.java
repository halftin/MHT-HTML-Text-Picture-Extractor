package com.mhtextractor.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.mhtextractor.uiport.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Explicit additive projections shared by detail queries and preview image manifests. */
final class IpcDetailCodec {
    private final ObjectMapper json;
    private final IpcFiles files;
    private final IpcWorkspaceCodec workspace;
    private final IpcParamCodec params;
    IpcDetailCodec(ObjectMapper json, IpcFiles files) {
        this.json=json; this.files=files;
        workspace=new IpcWorkspaceCodec(json,files); params=new IpcParamCodec(json);
    }
    ObjectNode target(TargetId id) { return json.createObjectNode().put("path",id.path()).put("groupNo",id.groupNo()); }
    ObjectNode parameters(ParameterStateVm vm) {
        var value=json.createObjectNode().put("textConfirmed",vm.textConfirmed()).put("imageConfirmed",vm.imageConfirmed())
                .put("count",vm.count()).put("uniform",vm.uniform()).put("hasEffective",vm.hasEffective());
        value.set("textGlobal",vm.textGlobal()==null ? json.nullNode() : params.config(vm.textGlobal()));
        value.set("imageGlobal",vm.imageGlobal()==null ? json.nullNode() : params.config(vm.imageGlobal()));
        var targets=value.putArray("targets"); vm.targets().forEach(t -> targets.add(target(t)));
        var states=value.putArray("states");
        for (var state:vm.states()) {
            var item=states.addObject().put("independent",state.independent()).put("effective",state.effective()).put("source",state.source());
            item.set("target",target(state.target()));
            item.set("config",state.config()==null ? json.nullNode() : params.config(state.config()));
        }
        return value;
    }
    ObjectNode preStart(PreStartStateVm vm) {
        var value=json.createObjectNode().put("textConfirmed",vm.textConfirmed()).put("imageConfirmed",vm.imageConfirmed())
                .put("subsetMergedName",vm.subsetMergedName());
        var groups=value.putArray("defaultNamedGroups"); vm.defaultNamedGroups().forEach(groups::add);
        if(vm.denial()==null) value.putNull("denial");
        else {
            var denial=value.putObject("denial").put("key",vm.denial().key()).put("level",vm.denial().level());
            vm.denial().namedParams().forEach(denial.putObject("namedParams")::put);
        }
        return value;
    }
    ArrayNode presentation(List<PresentationVm> items) throws IOException {
        var value=json.createArrayNode();
        for(var item:items) {
            var node=value.addObject().put("path",item.path()).put("displayName",item.displayName()).put("parsing",item.parsing())
                    .put("imageMode",item.imageMode()).put("status",item.status()).put("head",item.head()).put("tail",item.tail());
            node.set("textFile",workspace.reference(item.textFile()));
            var images=node.putArray("images"); for(var image:item.images()) images.add(image(image));
        }
        return value;
    }
    ObjectNode image(PortEvent.ImageFile image) throws IOException {
        var value=files.borrowImage(Path.of(image.path()),image.mediaType());
        value.put("imagePath",image.path()).put("extension",image.extension()).put("coreName",image.coreName())
                .put("displayName",image.displayName()).put("width",image.width()).put("height",image.height()).put("index",image.index());
        value.putObject("dimensions").put("width",image.width()).put("height",image.height());
        return value;
    }
}
