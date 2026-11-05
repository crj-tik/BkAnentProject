package com.bkanent.listing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.bkanent.listing.mapper.ListingAssetMapper;
import com.bkanent.listing.mapper.ListingMapper;
import com.bkanent.listing.mapper.ListingPrivateInfoMapper;
import com.bkanent.listing.service.impl.ListingDomainServiceImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * ListingMybatisH2Fixture H2 + MyBatis Plus 测试引导。
 * 以真实 SQL 执行素材/敏感资料/在售分页查询（含逻辑删除与 wrapper 条件），供 3.6/3.7/4.1 测试复用。
 */
public final class ListingMybatisH2Fixture implements AutoCloseable {

    /**
     * 字段：jdbc（直接 SQL 断言与造数）。
     */
    public final JdbcTemplate jdbc;
    /**
     * 字段：session（MyBatis 会话，需保持打开）。
     */
    private final SqlSession session;
    /**
     * 字段：listingMapper。
     */
    public final ListingMapper listingMapper;
    /**
     * 字段：assetMapper。
     */
    public final ListingAssetMapper assetMapper;
    /**
     * 字段：privateInfoMapper。
     */
    public final ListingPrivateInfoMapper privateInfoMapper;

    public ListingMybatisH2Fixture() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        createTables();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("listing-h2-test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(ListingMapper.class);
        configuration.addMapper(ListingAssetMapper.class);
        configuration.addMapper(ListingPrivateInfoMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
        listingMapper = session.getMapper(ListingMapper.class);
        assetMapper = session.getMapper(ListingAssetMapper.class);
        privateInfoMapper = session.getMapper(ListingPrivateInfoMapper.class);
    }

    /**
     * 建表：listing_info / listing_asset / listing_private_info（与受管迁移结构一致）。
     */
    private void createTables() {
        jdbc.execute("CREATE TABLE listing_info ("
                + "id BIGINT PRIMARY KEY, broker_id BIGINT, owner_customer_id BIGINT, "
                + "title VARCHAR(255), address VARCHAR(512), community_name VARCHAR(128), district_code VARCHAR(32), "
                + "layout VARCHAR(64), area DECIMAL(10,2), total_price DECIMAL(12,2), asking_price_yuan DECIMAL(16,2), "
                + "status VARCHAR(32), floor_level VARCHAR(64), decoration VARCHAR(64), school_zone VARCHAR(255), "
                + "traffic VARCHAR(255), owner_name VARCHAR(128), certificate_no VARCHAR(255), "
                + "property_certificate_url VARCHAR(512), contract_url VARCHAR(512), image_urls CLOB, "
                + "floor_plan_urls CLOB, video_urls CLOB, ocr_status VARCHAR(32), verification_status VARCHAR(32), "
                + "verification_source VARCHAR(64), verification_remark VARCHAR(512), version BIGINT, "
                + "created_at TIMESTAMP NULL, updated_at TIMESTAMP NULL, deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE listing_asset ("
                + "id BIGINT NOT NULL AUTO_INCREMENT, listing_id BIGINT NOT NULL, asset_type VARCHAR(32) NOT NULL, "
                + "storage_key VARCHAR(512) NOT NULL, visibility VARCHAR(16) NOT NULL, sort_order INT NOT NULL DEFAULT 0, "
                + "status VARCHAR(16) NOT NULL, created_by BIGINT NOT NULL, created_at TIMESTAMP NULL, "
                + "updated_at TIMESTAMP NULL, deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY (id))");
        jdbc.execute("CREATE TABLE listing_private_info ("
                + "listing_id BIGINT NOT NULL, certificate_no_ciphertext VARBINARY(1024) NULL, "
                + "certificate_key_id VARCHAR(128) NULL, created_at TIMESTAMP NULL, updated_at TIMESTAMP NULL, "
                + "PRIMARY KEY (listing_id))");
    }

    /**
     * 构造真实领域服务（注入 H2 会话 mapper）。
     */
    public ListingDomainServiceImpl newDomainService() {
        ListingDomainServiceImpl service = new ListingDomainServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", listingMapper);
        return service;
    }

    /**
     * 造数：插入一条房源（其余列可后续 UPDATE 补充）。
     */
    public void insertListing(long id, long brokerId, String status, String updatedAt,
                              BigDecimal area, BigDecimal askingYuan, BigDecimal totalWan,
                              String districtCode, String communityName, String layout, int deleted) {
        jdbc.update("INSERT INTO listing_info (id, broker_id, status, updated_at, area, asking_price_yuan, "
                        + "total_price, district_code, community_name, layout, deleted, version, title, address) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,0,'测试房源','测试地址')",
                id, brokerId, status, Timestamp.valueOf(updatedAt), area, askingYuan, totalWan,
                districtCode, communityName, layout, deleted);
    }

    @Override
    public void close() {
        session.close();
    }
}
