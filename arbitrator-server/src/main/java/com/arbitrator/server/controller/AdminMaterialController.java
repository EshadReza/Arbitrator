package com.arbitrator.server.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.arbitrator.common.api.ApiPaths;
import com.arbitrator.common.dto.MaterialDto;
import com.arbitrator.server.service.MaterialService;

/**
 * The instructor's side of materials (FR-07 sibling to announcements).
 * Loopback + ADMIN JWT like every other admin route (decision D3).
 */
@RestController
public class AdminMaterialController {

    private final MaterialService materials;

    public AdminMaterialController(MaterialService materials) {
        this.materials = materials;
    }

    @GetMapping(ApiPaths.ADMIN_MATERIALS)
    public List<MaterialDto> list(@PathVariable long id) {
        return materials.forContest(id);
    }

    /** Uploading is visible to every connected client immediately (broadcast). */
    @PostMapping(ApiPaths.ADMIN_MATERIALS)
    public MaterialDto upload(@PathVariable long id, @RequestParam("file") MultipartFile file) {
        return materials.upload(id, file);
    }

    @DeleteMapping(ApiPaths.ADMIN_MATERIAL_BY_ID)
    public ResponseEntity<Void> delete(@PathVariable long id) {
        materials.delete(id);
        return ResponseEntity.noContent().build();
    }
}
