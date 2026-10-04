package ru.anblazhnov.springaidocloader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

final class SourceText {
    private static final Pattern XML_ENCODING = Pattern.compile(
            "^\\s*<\\?xml[^?]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);

    record Decoded(String text, String encoding, String fileHash) { }

    static Decoded read(Path path, DiscoveryProperties properties) throws IOException {
        // Bound the actual read too: the file may grow after its attributes were inspected.
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes((int) properties.getMaxFileSize() + 1);
        }
        if (bytes.length > properties.getMaxFileSize()) throw new IOException("File exceeds max-file-size");
        Charset charset = Charset.forName(properties.getEncoding());
        int offset = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            charset = StandardCharsets.UTF_8;
            offset = 3;
        }
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        }
        else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        }
        else if (isXml(DiscoveredFile.FileKind.fromFilename(path))) {
            if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == '<' && bytes[2] == 0 && bytes[3] == '?') {
                charset = StandardCharsets.UTF_16BE;
            }
            else if (bytes.length >= 4 && bytes[0] == '<' && bytes[1] == 0 && bytes[2] == '?' && bytes[3] == 0) {
                charset = StandardCharsets.UTF_16LE;
            }
            else {
                var declaration = XML_ENCODING.matcher(new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.ISO_8859_1));
                if (declaration.find()) charset = Charset.forName(declaration.group(1));
            }
        }
        String text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        if (text.indexOf('\0') >= 0) throw new IOException("Binary/NUL content is not indexable text");
        return new Decoded(text, charset.name(), SourceIdentity.hash(bytes));
    }

    static boolean isXml(DiscoveredFile.FileKind kind) {
        return kind == DiscoveredFile.FileKind.POM || kind == DiscoveredFile.FileKind.WSDL
                || kind == DiscoveredFile.FileKind.XSD || kind == DiscoveredFile.FileKind.XML;
    }
}
