package com.vaultdrive.job;

public enum JobType {
    FILE_PURGE("FILE"), FOLDER_PURGE("FOLDER");

    private final String aggregateType;

    JobType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String aggregateType() {
        return aggregateType;
    }
}
