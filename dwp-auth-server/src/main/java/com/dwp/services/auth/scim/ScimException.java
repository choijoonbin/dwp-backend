package com.dwp.services.auth.scim;

public class ScimException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String scimType;

    public ScimException(int status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
    }

    public int status() {
        return status;
    }

    public String scimType() {
        return scimType;
    }

    public static ScimException notFound() {
        return new ScimException(404, null, "The SCIM resource was not found.");
    }

    public static ScimException conflict(String detail) {
        return new ScimException(409, "uniqueness", detail);
    }

    public static ScimException invalidValue(String detail) {
        return new ScimException(400, "invalidValue", detail);
    }

    public static ScimException invalidFilter(String detail) {
        return new ScimException(400, "invalidFilter", detail);
    }

    public static ScimException preconditionFailed(String detail) {
        return new ScimException(412, null, detail);
    }
}
