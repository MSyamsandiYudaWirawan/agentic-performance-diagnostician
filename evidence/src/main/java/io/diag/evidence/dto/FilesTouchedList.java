package io.diag.evidence.dto;

import java.util.List;

/**
 * Column wrapper for iteration.files_touched (a JSON array of
 * {@link FilesTouchedDto}). Spring Data JDBC maps List properties as
 * child-table associations — never as simple columns — so the list is
 * wrapped; the wrappers' converters serialize the bare JSON array, so the
 * column shape is unchanged: [{"path", "linesBefore", "linesAfter"}, ...].
 */
public record FilesTouchedList(
        List<FilesTouchedDto> files
) {

    public static FilesTouchedList of(List<FilesTouchedDto> files) {
        return new FilesTouchedList(files);
    }
}
