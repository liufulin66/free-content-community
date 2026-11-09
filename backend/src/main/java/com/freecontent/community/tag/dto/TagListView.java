package com.freecontent.community.tag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "TagList", requiredProperties = "items")
public record TagListView(List<TagView> items) {
    public TagListView { items = List.copyOf(items); }
}
