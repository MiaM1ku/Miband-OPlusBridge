// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 6.11.7 renamed the account helper, so the import reads the signed-in ssoid from the options
 * Health hands to the data engine. The shapes below mirror those host classes.
 */
public final class OHealthHostContractTest {
    public static final class ReadOption {
        private final String ssoid;
        ReadOption(String ssoid) { this.ssoid = ssoid; }
        public String getSsoid() { return ssoid; }
    }

    public static final class Row {
        private final String ssoid;
        Row(String ssoid) { this.ssoid = ssoid; }
        public String getSsoid() { return ssoid; }
    }

    public static final class InsertOption {
        private final List<Row> rows;
        InsertOption(List<Row> rows) { this.rows = rows; }
        public List<Row> getDatas() { return rows; }
    }

    public static final class Unrelated {
        public String getDataTable() { return "1002"; }
    }

    @Test public void readOptionCarriesTheAccount() {
        assertEquals("ssoid-1", OHealthHealthImportHook.HostContract.ssoidOf(new ReadOption("ssoid-1")));
    }

    @Test public void insertOptionTakesTheFirstRowAccount() {
        assertEquals("row-ssoid", OHealthHealthImportHook.HostContract.ssoidOf(
                new InsertOption(List.of(new Row("row-ssoid"), new Row("other")))));
    }

    @Test public void blankOrMissingAccountStaysUnknown() {
        assertNull(OHealthHealthImportHook.HostContract.ssoidOf(new ReadOption("  ")));
        assertNull(OHealthHealthImportHook.HostContract.ssoidOf(new InsertOption(List.of())));
        assertNull(OHealthHealthImportHook.HostContract.ssoidOf(new Unrelated()));
        assertNull(OHealthHealthImportHook.HostContract.ssoidOf(null));
    }
}
