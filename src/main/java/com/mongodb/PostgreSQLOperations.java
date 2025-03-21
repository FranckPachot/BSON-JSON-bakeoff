package com.mongodb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.sql.Array;


import org.json.JSONObject;
import org.postgresql.util.PGobject;

public class PostgreSQLOperations implements DatabaseOperations {
    private Connection connection;
    private boolean isYugabyteDB=false;
    private Random rand = new Random();
    private PreparedStatement stmt;
    
    @Override
    public void initializeDatabase(String connectionString) {
        try {
            connection = DriverManager.getConnection(connectionString);
            PreparedStatement stmt = connection.prepareStatement("SELECT version()");
            // test the version for PostgreSQL-compatible databases like YugabyteDB
            ResultSet rs = stmt.executeQuery();
            rs.next();
            System.out.println(rs.getString(1));
            isYugabyteDB = rs.getString(1).contains("-YB-");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void dropAndCreateCollections(List<String> collectionNames) {
        try {
            PreparedStatement dropStmt;
            PreparedStatement createStmt;
            for (String collectionName : collectionNames) {
                dropStmt = connection.prepareStatement(String.format("DROP TABLE IF EXISTS %s", collectionName));
    
                createStmt = connection.prepareStatement(String.format("CREATE %s TABLE %s  (id SERIAL PRIMARY KEY, data %s, indexArray TEXT[])"
                    , isYugabyteDB ? "" : "UNLOGGED", collectionName, Main.jsonType)
                 )                ;

                dropStmt.execute();
                createStmt.execute();

                createStmt = connection.prepareStatement(String.format("ALTER TABLE %s SET (autovacuum_enabled = false);", collectionName));
                // YugabyteDB doesn't have VACUUM (not needed)
                if (!isYugabyteDB) {
                    createStmt.execute();
                }
                
                if (collectionName.equals("indexed")) {
                    createStmt = connection.prepareStatement("CREATE INDEX index1 ON indexed USING gin (indexarray)");
                    createStmt.execute();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public List<JSONObject> generateDocuments(List<String> objectIds) {
        List<JSONObject> documents = new ArrayList<>();
        for (String id : objectIds) {
            JSONObject json = new JSONObject();
            json.put("id", id);
            documents.add(json);
        }
        return documents;
    }

    @Override
public long insertDocuments(String collectionName, List<JSONObject> documents, int dataSize, boolean splitPayload) {
    // Prepare SQL statement
    String sql = "INSERT INTO " + collectionName + " (data, indexarray) VALUES (?, ?)";
    for (int i = 1; i < Main.batchSize; i++) {
        sql += ", (?, ?)";
    }
    
    try {
        stmt = connection.prepareStatement(sql);
        int setIdx = 0;
        byte[] bytes = new byte[dataSize];
        rand.nextBytes(bytes);
        
        JSONObject dataJson = new JSONObject();
        if (splitPayload) {
            dataJson.clear();
            int length = dataSize / Main.numAttrs;
            for (int i = 0; i < Main.numAttrs; i++) {
                dataJson.put("data" + i, Arrays.copyOfRange(bytes, i * length, (i + 1) * length));
            }
        } else {
            dataJson.put("data", bytes);
        }
        
        long startTime = System.currentTimeMillis();
        PGobject pgo = new PGobject();
        pgo.setType(Main.jsonType);
        for (JSONObject json : documents) {            
            json.put("payload", dataJson);
            pgo.setValue(json.toString());
            
            stmt.setObject(++setIdx, pgo);  // Set `data` parameter
            json.remove("payload");
            
            List<String> indexAttrs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                indexAttrs.add(documents.get(rand.nextInt(documents.size())).getString("id"));
            }
            
            String[] indexArray = indexAttrs.toArray(new String[0]);
            Array sqlArray = connection.createArrayOf("text", indexArray);
            
            stmt.setArray(++setIdx, sqlArray);  // Set `indexarray` parameter
            
            // Check if we've reached the batch size limit
            if (setIdx / 2 == Main.batchSize) {
                stmt.executeBatch();  // Execute and clear batch
                setIdx = 0;           // Reset the index counter
            }
        }
        // Execute any remaining statements in the batch
        if (setIdx > 0) {
            stmt.executeBatch();
        }
        
        return System.currentTimeMillis() - startTime;
    } catch (SQLException e) {
        e.printStackTrace();
        return -1;
    }
}
    
    @Override
    public int queryDocumentsById(String collectionName, String id) {
        String sql = "SELECT data FROM " + collectionName + " WHERE ARRAY[?::string] <@ indexarray";
        try {
            stmt = connection.prepareStatement(sql);
            stmt.setString(1, id);
            ResultSet rs = stmt.executeQuery();
            //ArrayList<JSONObject> rowData = new ArrayList<JSONObject>(); // Declare rowData before executeQuery
            int count = 0;
            while (rs.next()) {
                rs.getString("data");
                // Process the data as needed
                //rowData.add(new JSONObject(data)); // Parse the data string into a JSONObject
                count++;
            }
            return count;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int queryDocumentsByIdUsingLookup(String collectionName, String id) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'queryDocumentsByIdUsingLookup'");
    }

    @Override
    public int queryDocumentsByIdWithInCondition(String collectionName, JSONObject document) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'queryDocumentsByIdWithInCondition'");
    }
}
