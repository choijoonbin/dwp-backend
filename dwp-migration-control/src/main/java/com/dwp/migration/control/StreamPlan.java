package com.dwp.migration.control;

record StreamPlan(
        String streamKey,
        String schema,
        String historyTable,
        String location,
        boolean baselineOnMigrate,
        boolean createSchemas) {
}
