package com.example.langchain4j.enums;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 19:36
 * @Modify:
 **/
public enum UserActivityStatus {
    ACTIVE(1, "活跃"),
    INACTIVE(2, "不活跃");

    private Integer value;
    private String desc;

    UserActivityStatus(Integer value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public static UserActivityStatus fromValue(Integer value) {
        for (UserActivityStatus status : UserActivityStatus.values()) {
            if (status.getValue().equals(value)) {
                return status;
            }
        }
        return null;
    }

    public Integer getValue() {
        return value;
    }

    public String getDesc() {
        return desc;
    }
}
