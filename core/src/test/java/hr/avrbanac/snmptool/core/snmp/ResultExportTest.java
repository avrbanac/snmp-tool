package hr.avrbanac.snmptool.core.snmp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hr.avrbanac.snmptool.core.mib.model.Oid;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultExportTest {

    private static final List<ResultFormatter.Formatted> ROWS = List.of(
            row("1.3.6.1.2.1.1.1.0", "sysDescr.0", "OCTET STRING", "Linux, \"core\"\r\nkernel 6.1", "4c 69"),
            row("1.3.6.1.2.1.2.2.1.8.2", "ifOperStatus.2", "INTEGER", "up(1)", "1"),
            row("1.3.6.1.4.1.99.1.0", "1.3.6.1.4.1.99.1.0", "Integer32", "-5", "-5"),
            row("1.3.6.1.2.1.1.5.0", "sysName.0", "OCTET STRING", "=HYPERLINK(\"x\")", "3d"));

    private static ResultFormatter.Formatted row(String oid, String name, String type, String value, String raw) {
        return new ResultFormatter.Formatted(Oid.parse(oid), name, Optional.empty(), type, value, raw);
    }

    private static String write(ResultExport.Format format, List<String> header) throws IOException {
        StringWriter out = new StringWriter();
        ResultExport.write(out, format, ROWS, header);
        return out.toString();
    }

    @Test
    void csvHasHeaderQuotingAndCrlf() throws IOException {
        assertEquals("""
                Name,Value,Type,OID,Raw\r
                sysDescr.0,"Linux, ""core""\r
                kernel 6.1",OCTET STRING,1.3.6.1.2.1.1.1.0,4c 69\r
                ifOperStatus.2,up(1),INTEGER,1.3.6.1.2.1.2.2.1.8.2,1\r
                1.3.6.1.4.1.99.1.0,-5,Integer32,1.3.6.1.4.1.99.1.0,-5\r
                sysName.0,"'=HYPERLINK(""x"")",OCTET STRING,1.3.6.1.2.1.1.5.0,3d\r
                """, write(ResultExport.Format.CSV, List.of("ignored in CSV")));
    }

    @Test
    void csvGuardsFormulasButNotNumbers() {
        assertEquals("'+cmd", ResultExport.csvField("+cmd"));
        assertEquals("'@SUM(A1)", ResultExport.csvField("@SUM(A1)"));
        assertEquals("'-x", ResultExport.csvField("-x"));
        assertEquals("-12.5", ResultExport.csvField("-12.5"));
        assertEquals("\" padded \"", ResultExport.csvField(" padded "));
        assertEquals("", ResultExport.csvField(""));
    }

    @Test
    void textHasOneLinePerValue() throws IOException {
        assertEquals("""
                # Walk of system on lab (10.0.0.1:161, v2c)
                # 4 values

                sysDescr.0 (1.3.6.1.2.1.1.1.0) = OCTET STRING: Linux, "core"\\nkernel 6.1
                ifOperStatus.2 (1.3.6.1.2.1.2.2.1.8.2) = INTEGER: up(1)
                1.3.6.1.4.1.99.1.0 = Integer32: -5
                sysName.0 (1.3.6.1.2.1.1.5.0) = OCTET STRING: =HYPERLINK("x")
                """, write(ResultExport.Format.TEXT, List.of("Walk of system on lab (10.0.0.1:161, v2c)", "4 values")));
    }

    @Test
    void writesUtf8FileAndKnowsFormatsByExtension(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("walk.CSV");
        ResultExport.write(file, ResultExport.Format.CSV, List.of(row("1.3", "x", "OCTET STRING", "čćž", "")), List.of());
        assertEquals("Name,Value,Type,OID,Raw\r\nx,čćž,OCTET STRING,1.3,\r\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(Optional.of(ResultExport.Format.CSV), ResultExport.Format.forFile(file));
        assertEquals(Optional.of(ResultExport.Format.TEXT), ResultExport.Format.forFile(dir.resolve("a.txt")));
        assertEquals(Optional.empty(), ResultExport.Format.forFile(dir.resolve("a.json")));
    }
}
