package com.drivelog.server.domain.wash;

/** Representative city grid for each region; mid-term forecasts cover wider KMA land areas. */
public enum WashRegion {
    SEOUL("서울", 60, 127, "11B00000"),
    BUSAN("부산", 98, 76, "11H20000"),
    DAEGU("대구", 89, 90, "11H10000"),
    INCHEON("인천", 55, 124, "11B00000"),
    GWANGJU("광주", 58, 74, "11F20000"),
    DAEJEON("대전", 67, 100, "11C20000"),
    ULSAN("울산", 102, 84, "11H20000"),
    SEJONG("세종", 66, 103, "11C20000"),
    GYEONGGI("경기 · 수원", 60, 121, "11B00000"),
    GANGWON("강원 · 춘천", 73, 134, "11D10000"),
    CHUNGBUK("충북 · 청주", 69, 107, "11C10000"),
    CHUNGNAM("충남 · 홍성", 55, 106, "11C20000"),
    JEONBUK("전북 · 전주", 63, 89, "11F10000"),
    JEONNAM("전남 · 무안", 52, 71, "11F20000"),
    GYEONGBUK("경북 · 안동", 91, 106, "11H10000"),
    GYEONGNAM("경남 · 창원", 90, 77, "11H20000"),
    JEJU("제주", 52, 38, "11G00000");

    public final String displayName;
    public final int nx;
    public final int ny;
    public final String midTermRegionCode;

    WashRegion(String displayName, int nx, int ny, String midTermRegionCode) {
        this.displayName = displayName;
        this.nx = nx;
        this.ny = ny;
        this.midTermRegionCode = midTermRegionCode;
    }
}
