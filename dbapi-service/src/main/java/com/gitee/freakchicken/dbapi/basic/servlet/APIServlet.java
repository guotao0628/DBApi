package com.gitee.freakchicken.dbapi.basic.servlet;

import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.gitee.freakchicken.dbapi.basic.domain.DataSource;
import com.gitee.freakchicken.dbapi.basic.service.ApiConfigService;
import com.gitee.freakchicken.dbapi.basic.service.ApiService;
import com.gitee.freakchicken.dbapi.basic.service.DataSourceService;
import com.gitee.freakchicken.dbapi.basic.service.IPService;
import com.gitee.freakchicken.dbapi.basic.util.JdbcUtil;
import com.gitee.freakchicken.dbapi.basic.util.PoolManager;
import com.gitee.freakchicken.dbapi.basic.util.SqlEngineUtil;
import com.gitee.freakchicken.dbapi.basic.util.ThreadUtils;
import com.gitee.freakchicken.dbapi.common.ApiConfig;
import com.gitee.freakchicken.dbapi.common.ApiSql;
import com.gitee.freakchicken.dbapi.common.ResponseDto;
import com.gitee.freakchicken.dbapi.plugin.AlarmPlugin;
import com.gitee.freakchicken.dbapi.plugin.CachePlugin;
import com.gitee.freakchicken.dbapi.plugin.PluginManager;
import com.gitee.freakchicken.dbapi.plugin.TransformPlugin;
import com.github.freakchick.orange.SqlMeta;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class APIServlet extends HttpServlet {

    @Autowired
    ApiConfigService apiConfigService;
    @Autowired
    DataSourceService dataSourceService;
    @Autowired
    ApiService apiService;

    @Autowired
    IPService ipService;

    @Value("${dbapi.api.context}")
    String apiContext;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.debug("servlet execute");
        String servletPath = request.getRequestURI();
        servletPath = servletPath.substring(apiContext.length() + 2);

        PrintWriter out = null;
        try {
            out = response.getWriter();
            ResponseDto responseDto = process(servletPath, request, response);
            out.append(JSON.toJSONString(responseDto));

        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.append(JSON.toJSONString(ResponseDto.fail(e.toString())));
            log.error(e.toString(), e);
        } finally {
            if (out != null)
                out.close();
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        doGet(req, resp);
    }

    public ResponseDto process(String path, HttpServletRequest request, HttpServletResponse response) {

//            // ????????????????????????
        ApiConfig config = apiConfigService.getConfig(path);
        if (config == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return ResponseDto.fail("Api not exists");
        }

        try {
            DataSource datasource = dataSourceService.detail(config.getDatasourceId());
            if (datasource == null) {
                response.setStatus(500);
                return ResponseDto.fail("Datasource not exists!");
            }

            Map<String, Object> sqlParam = getParams(request, config);

            //?????????????????????
            if (StringUtils.isNoneBlank(config.getCachePlugin())) {
                CachePlugin cachePlugin = PluginManager.getCachePlugin(config.getCachePlugin());
                Object o = cachePlugin.get(config, sqlParam);
                if (o != null) {
                    return ResponseDto.apiSuccess(o); //?????????????????????????????????
                }
            }

            List<ApiSql> sqlList = config.getSqlList();
            DruidPooledConnection connection = PoolManager.getPooledConnection(datasource);
            //??????????????????
            boolean flag = config.getOpenTrans() == 1 ? true : false;
            //??????sql
            List<Object> dataList = executeSql(connection, sqlList, sqlParam, flag);

            //??????????????????
            for (int i = 0; i < sqlList.size(); i++) {
                ApiSql apiSql = sqlList.get(i);
                Object data = dataList.get(i);
                //???????????????sql????????????sql????????????????????????????????????
                if (data instanceof Iterable && StringUtils.isNotBlank(apiSql.getTransformPlugin())) {
                    log.info("transform plugin execute");
                    List<JSONObject> sourceData = (List<JSONObject>) (data); //?????????sql????????????????????????????????????????????????????????????sql???????????????????????????
                    TransformPlugin transformPlugin = PluginManager.getTransformPlugin(apiSql.getTransformPlugin());
                    Object resData = transformPlugin.transform(sourceData, apiSql.getTransformPluginParams());
                    dataList.set(i, resData);//???????????????
                }
            }
            Object res = dataList;
            //??????????????????sql,??????????????????????????????
            if (dataList.size() == 1) {
                res = dataList.get(0);
            }
            ResponseDto dto = ResponseDto.apiSuccess(res);
            //????????????
            if (StringUtils.isNoneBlank(config.getCachePlugin())) {
                CachePlugin cachePlugin = PluginManager.getCachePlugin(config.getCachePlugin());
                cachePlugin.set(config, sqlParam, dto.getData());
            }
            return dto;
        } catch (Exception e) {
            //??????API???????????????
            if (StringUtils.isNotBlank(config.getAlarmPlugin())) {
                try {
                    log.info(config.getAlarmPlugin());
                    AlarmPlugin alarmPlugin = PluginManager.getAlarmPlugin(config.getAlarmPlugin());
                    ThreadUtils.submitAlarmTask(new Runnable() {
                        @Override
                        public void run() {
                            alarmPlugin.alarm(e, config, request, config.getAlarmPluginParam());
                        }
                    });
                } catch (Exception error) {
                    log.error(config.getAlarmPlugin() + " error!", error);
                }
            }
            throw new RuntimeException(e.getMessage());
        }
    }

    public List<Object> executeSql(Connection connection, List<ApiSql> sqlList, Map<String, Object> sqlParam, boolean flag) {
        List<Object> dataList = new ArrayList<>();
        try {
            if (flag)
                connection.setAutoCommit(false);
            else
                connection.setAutoCommit(true);
            for (ApiSql apiSql : sqlList) {
                SqlMeta sqlMeta = SqlEngineUtil.getEngine().parse(apiSql.getSqlText(), sqlParam);
                Object data = JdbcUtil.executeSql(connection, sqlMeta.getSql(), sqlMeta.getJdbcParamValues());
                dataList.add(data);
            }
            if (flag)
                connection.commit();
            return dataList;
        } catch (Exception e) {
            try {
                if (flag)
                    connection.rollback();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
            throw new RuntimeException(e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private Map<String, Object> getParams(HttpServletRequest request, ApiConfig apiConfig) {
        /**
         * Content-Type????????????:
         * {@see <a href="https://www.w3.org/Protocols/rfc1341/4_Content-Type.html">Content-Type</a>}
         * type/subtype(;parameter)? type
         */
        String unParseContentType = request.getContentType();

        //??????????????????get???????????????????????????contentType???null
        if (unParseContentType == null) {
            unParseContentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE;
        }
        // issues/I57ZG2
        // ??????contentType ??????: appliation/json;charset=utf-8
        String[] contentTypeArr = unParseContentType.split(";");
        String contentType = contentTypeArr[0];

        Map<String, Object> params = null;
        //?????????application/json??????????????????????????????content-type?????????????????????????????????????????????????????????json body ??????
        if (contentType.equalsIgnoreCase(MediaType.APPLICATION_JSON_VALUE)) {
            JSONObject jo = getHttpJsonBody(request);
            params = JSONObject.parseObject(jo.toJSONString(), new TypeReference<Map<String, Object>>() {
            });
        }
        //?????????application/x-www-form-urlencoded?????????????????????????????????content-type??????????????????application/x-www-form-urlencoded
        else if (contentType.equalsIgnoreCase(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            if (MediaType.APPLICATION_FORM_URLENCODED_VALUE.equalsIgnoreCase(apiConfig.getContentType())) {
                params = apiService.getSqlParam(request, apiConfig);
            } else {
                throw new RuntimeException("this API only support content-type: " + apiConfig.getContentType() + ", but you use: " + contentType);
            }
        } else {
            throw new RuntimeException("content-type not supported: " + contentType);
        }

        return params;
    }

    private JSONObject getHttpJsonBody(HttpServletRequest request) {
        try {
            InputStreamReader in = new InputStreamReader(request.getInputStream(), "utf-8");
            BufferedReader br = new BufferedReader(in);
            StringBuilder sb = new StringBuilder();
            String line = null;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            JSONObject jsonObject = JSON.parseObject(sb.toString());
            return jsonObject;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {

        }
        return null;
    }

}
