package com.craftplanner.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * File helpers shared by the three JSON stores of the plan
 * ({@link PlanStore}, {@link RecipeChoices}, {@link RecipeCuts}).
 */
final class JsonFiles {

    private JsonFiles() {
    }

    /**
     * Writes JSON next to the target and moves it over, so a crash mid-write
     * never leaves half a file.
     *
     * <p>The stores read their files back on the next start and treat an
     * unparseable one as "nothing was saved", which would silently drop a plan
     * that was only interrupted while being written. A temporary file that is
     * moved into place leaves either the old content or the new one, never a
     * mixture. The move is asked of the filesystem atomically; where that is not
     * supported — another volume, some network mounts — it falls back to a plain
     * replace, which still keeps the half-written bytes out of the real file.</p>
     */
    static void writeAtomically(Path file, JsonElement json, Gson gson) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            gson.toJson(json, writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
