package io.github.tangbailin.novamarket.domain;

/** 一次原子扣减的返回值。 */
public record DeductResult(DeductCode code, int shardNo, String claimNo, String detail) {

    public static DeductResult success(int shardNo, String claimNo) {
        return new DeductResult(DeductCode.SUCCESS, shardNo, claimNo, null);
    }

    public static DeductResult duplicate(int shardNo, String claimNo) {
        return new DeductResult(DeductCode.DUPLICATE, shardNo, claimNo, "重复请求，返回首次结果");
    }

    public static DeductResult failed(DeductCode code, String detail) {
        return new DeductResult(code, -1, null, detail);
    }

    public boolean ok() {
        return code == DeductCode.SUCCESS;
    }

    /** 重复请求也应当返回成功（对用户而言结果一致），但不能再做任何副作用。 */
    public boolean successOrDuplicate() {
        return code == DeductCode.SUCCESS || code == DeductCode.DUPLICATE;
    }
}
