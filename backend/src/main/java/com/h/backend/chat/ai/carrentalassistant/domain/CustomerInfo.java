package com.h.backend.chat.ai.carrentalassistant.domain;

import dev.langchain4j.model.output.structured.Description;

/**
 * 会话期间代表客户信息
 */
public class CustomerInfo {
    private String name;
    private String customerId;
    private String bookingReference;
    private String carMake;
    private String carModel;
    private String carYear;
    @Description("客户所在位置（例如机场、城市、地址等）")
    private String location;

    /**
     * Default constructor for JSON serialization/deserialization.
     */
    public CustomerInfo() {
    }

    /**
     * 根据指定信息创建CustomerInfo实例
     *
     * @param name            客户姓名
     * @param customerId      客户编号
     * @param bookingReference 预订参考号
     * @param carMake         租赁车辆品牌
     * @param carModel        租赁车辆车型
     * @param carYear         租赁车辆年份
     * @param location        客户当前所在地
     */
    public CustomerInfo(String name, String customerId, String bookingReference,
                        String carMake, String carModel, String carYear,
                        String location) {
        this.name = name;
        this.customerId = customerId;
        this.bookingReference = bookingReference;
        this.carMake = carMake;
        this.carModel = carModel;
        this.carYear = carYear;
        this.location = location;
    }

    /**
     * 校验客户信息是否完整
     *
     * @return 所有必填信息齐全返回true，否则返回false
     */
    public boolean isComplete() {
        return name != null && !name.isEmpty() &&
               bookingReference != null && !bookingReference.isEmpty() &&
               carMake != null && !carMake.isEmpty() &&
               carModel != null && !carModel.isEmpty();
    }

    /**
     * 获取客户信息的字符串展示形式
     *
     * @return 包含客户详情的格式化字符串
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        
        if (name != null && !name.isEmpty()) {
            sb.append("Name: ").append(name).append("\n");
        }
        
        if (customerId != null && !customerId.isEmpty()) {
            sb.append("Customer ID: ").append(customerId).append("\n");
        }
        
        if (bookingReference != null && !bookingReference.isEmpty()) {
            sb.append("Booking Reference: ").append(bookingReference).append("\n");
        }
        
        if (carMake != null && !carMake.isEmpty() || carModel != null && !carModel.isEmpty() || carYear != null && !carYear.isEmpty()) {
            sb.append("Vehicle: ");
            if (carYear != null && !carYear.isEmpty()) {
                sb.append(carYear).append(" ");
            }
            if (carMake != null && !carMake.isEmpty()) {
                sb.append(carMake).append(" ");
            }
            if (carModel != null && !carModel.isEmpty()) {
                sb.append(carModel);
            }
            sb.append("\n");
        }
        
        if (location != null && !location.isEmpty()) {
            sb.append("Location: ").append(location).append("\n");
        }
        
        return sb.toString().trim();
    }

    // Getters and setters

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public void setBookingReference(String bookingReference) {
        this.bookingReference = bookingReference;
    }

    public String getCarMake() {
        return carMake;
    }

    public void setCarMake(String carMake) {
        this.carMake = carMake;
    }

    public String getCarModel() {
        return carModel;
    }

    public void setCarModel(String carModel) {
        this.carModel = carModel;
    }

    public String getCarYear() {
        return carYear;
    }

    public void setCarYear(String carYear) {
        this.carYear = carYear;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}
