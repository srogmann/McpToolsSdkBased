package org.rogmann.mcp2sdk.js;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for reading, writing and listing text files
 * in a fixed work directory.
 * <p>
 * File names must not contain a file separator;
 * an {@link IllegalArgumentException} is thrown otherwise.
 */
public class ToolStore {

    /** Fixed work directory. */
    private static final String WORK_DIR = "/tmp/js-work/";

    /**
     * Writes a text file with the given content.
     *
     * @param fileName file name (without path separators)
     * @param content  text content to write
     * @throws IllegalArgumentException if the file name contains a file separator
     * @throws RuntimeException         if an I/O error occurs
     */
    public static void writeFile(String fileName, String content) {
        validateFileName(fileName);
        try {
            Path dir = Paths.get(WORK_DIR);
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + fileName, e);
        }
    }

    /**
     * Reads the content of a text file.
     *
     * @param fileName file name (without path separators)
     * @return the file content as a string
     * @throws IllegalArgumentException if the file name contains a file separator
     * @throws RuntimeException         if the file does not exist or an I/O error occurs
     */
    public static String readFile(String fileName) {
        validateFileName(fileName);
        try {
            Path file = Paths.get(WORK_DIR).resolve(fileName);
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + fileName, e);
        }
    }

    /**
     * Lists all regular file names in the work directory.
     *
     * @return list of file names (without directory path)
     * @throws RuntimeException if an I/O error occurs
     */
    public static List<String> readFileNames() {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(WORK_DIR))) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    names.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list files in work directory", e);
        }
        return names;
    }

    /**
     * Validates that the given file name does not contain a file separator.
     *
     * @param fileName the file name to check
     * @throws IllegalArgumentException if the file name contains a separator
     */
    private static void validateFileName(String fileName) {
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "File name must not contain a file separator: " + fileName);
        }
    }
}
