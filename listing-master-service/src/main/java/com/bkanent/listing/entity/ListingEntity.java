package com.bkanent.listing.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * ListingEntity 房源实体类。
 */
@TableName("listing_info")
public class ListingEntity extends BaseEntity {

    /**
     * 业务属性：brokerId。
     */
    private Long brokerId;
    /**
     * 业务属性：ownerCustomerId（Customer 域业主档案 ID，仅存量可空，非产权证明）。
     */
    private Long ownerCustomerId;
    /**
     * 业务属性：title。
     */
    private String title;
    /**
     * 业务属性：address。
     */
    private String address;
    /**
     * 业务属性：communityName（小区名称，筛选用）。
     */
    private String communityName;
    /**
     * 业务属性：districtCode（行政区域编码，筛选用）。
     */
    private String districtCode;
    /**
     * 业务属性：layout。
     */
    private String layout;
    /**
     * 业务属性：area。
     */
    private BigDecimal area;
    /**
     * 业务属性：totalPrice（兼容期万元字段，唯一写入口为统一领域命令）。
     */
    private BigDecimal totalPrice;
    /**
     * 业务属性：askingPriceYuan（内部挂牌价主值，CNY 元）。
     */
    private BigDecimal askingPriceYuan;
    /**
     * 业务属性：status。
     */
    private String status;
    /**
     * 业务属性：floorLevel。
     */
    private String floorLevel;
    /**
     * 业务属性：decoration。
     */
    private String decoration;
    /**
     * 业务属性：schoolZone。
     */
    private String schoolZone;
    /**
     * 业务属性：traffic。
     */
    private String traffic;
    /**
     * 业务属性：ownerName。
     */
    private String ownerName;
    /**
     * 业务属性：certificateNo。
     */
    private String certificateNo;
    /**
     * 业务属性：propertyCertificateUrl。
     */
    private String propertyCertificateUrl;
    /**
     * 业务属性：contractUrl。
     */
    private String contractUrl;
    /**
     * 业务属性：imageUrls。
     */
    private String imageUrls;
    /**
     * 业务属性：floorPlanUrls。
     */
    private String floorPlanUrls;
    /**
     * 业务属性：videoUrls。
     */
    private String videoUrls;
    /**
     * 业务属性：ocrStatus。
     */
    private String ocrStatus;
    /**
     * 业务属性：verificationStatus。
     */
    private String verificationStatus;
    /**
     * 业务属性：verificationSource。
     */
    private String verificationSource;
    /**
     * 业务属性：verificationRemark。
     */
    private String verificationRemark;
    /**
     * 业务属性：version（乐观并发版本，影响可见事实或状态的修改递增；手动条件更新，不使用 @Version）。
     */
    private Long version;

    public Long getBrokerId() {
        return brokerId;
    }

    public void setBrokerId(Long brokerId) {
        this.brokerId = brokerId;
    }

    public Long getOwnerCustomerId() {
        return ownerCustomerId;
    }

    public void setOwnerCustomerId(Long ownerCustomerId) {
        this.ownerCustomerId = ownerCustomerId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCommunityName() {
        return communityName;
    }

    public void setCommunityName(String communityName) {
        this.communityName = communityName;
    }

    public String getDistrictCode() {
        return districtCode;
    }

    public void setDistrictCode(String districtCode) {
        this.districtCode = districtCode;
    }

    public String getLayout() {
        return layout;
    }

    public void setLayout(String layout) {
        this.layout = layout;
    }

    public BigDecimal getArea() {
        return area;
    }

    public void setArea(BigDecimal area) {
        this.area = area;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public BigDecimal getAskingPriceYuan() {
        return askingPriceYuan;
    }

    public void setAskingPriceYuan(BigDecimal askingPriceYuan) {
        this.askingPriceYuan = askingPriceYuan;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFloorLevel() {
        return floorLevel;
    }

    public void setFloorLevel(String floorLevel) {
        this.floorLevel = floorLevel;
    }

    public String getDecoration() {
        return decoration;
    }

    public void setDecoration(String decoration) {
        this.decoration = decoration;
    }

    public String getSchoolZone() {
        return schoolZone;
    }

    public void setSchoolZone(String schoolZone) {
        this.schoolZone = schoolZone;
    }

    public String getTraffic() {
        return traffic;
    }

    public void setTraffic(String traffic) {
        this.traffic = traffic;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getCertificateNo() {
        return certificateNo;
    }

    public void setCertificateNo(String certificateNo) {
        this.certificateNo = certificateNo;
    }

    public String getPropertyCertificateUrl() {
        return propertyCertificateUrl;
    }

    public void setPropertyCertificateUrl(String propertyCertificateUrl) {
        this.propertyCertificateUrl = propertyCertificateUrl;
    }

    public String getContractUrl() {
        return contractUrl;
    }

    public void setContractUrl(String contractUrl) {
        this.contractUrl = contractUrl;
    }

    public String getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(String imageUrls) {
        this.imageUrls = imageUrls;
    }

    public String getFloorPlanUrls() {
        return floorPlanUrls;
    }

    public void setFloorPlanUrls(String floorPlanUrls) {
        this.floorPlanUrls = floorPlanUrls;
    }

    public String getVideoUrls() {
        return videoUrls;
    }

    public void setVideoUrls(String videoUrls) {
        this.videoUrls = videoUrls;
    }

    public String getOcrStatus() {
        return ocrStatus;
    }

    public void setOcrStatus(String ocrStatus) {
        this.ocrStatus = ocrStatus;
    }

    public String getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(String verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public String getVerificationSource() {
        return verificationSource;
    }

    public void setVerificationSource(String verificationSource) {
        this.verificationSource = verificationSource;
    }

    public String getVerificationRemark() {
        return verificationRemark;
    }

    public void setVerificationRemark(String verificationRemark) {
        this.verificationRemark = verificationRemark;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
