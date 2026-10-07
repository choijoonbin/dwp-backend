package com.dwp.migration.control;

record StreamSeal(
        String streamKey,
        int historyMaxInstalledRank,
        int historyRowCount,
        String historySha256,
        int inventoryObjectCount,
        String inventorySha256,
        String adoptionReceiptSha256) {

    StreamSeal {
        if (streamKey == null || !streamKey.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IllegalStateException("Control stream seal key is not canonical");
        }
        if (historyMaxInstalledRank < 0
                || historyRowCount < 0
                || inventoryObjectCount < 1
                || historySha256 == null
                || !historySha256.matches("[0-9a-f]{64}")
                || inventorySha256 == null
                || !inventorySha256.matches("[0-9a-f]{64}")
                || adoptionReceiptSha256 == null
                || (!adoptionReceiptSha256.isEmpty()
                        && !adoptionReceiptSha256.matches("[0-9a-f]{64}"))) {
            throw new IllegalStateException("Control stream seal is not canonical");
        }
    }

    String toJson() {
        return "{" +
                "\"streamKey\":" + ControlValues.json(streamKey) + "," +
                "\"historyMaxInstalledRank\":" + historyMaxInstalledRank + "," +
                "\"historyRowCount\":" + historyRowCount + "," +
                "\"historySha256\":" + ControlValues.json(historySha256) + "," +
                "\"inventoryObjectCount\":" + inventoryObjectCount + "," +
                "\"inventorySha256\":" + ControlValues.json(inventorySha256) + "," +
                "\"adoptionReceiptSha256\":"
                + ControlValues.json(adoptionReceiptSha256) + "}";
    }
}
