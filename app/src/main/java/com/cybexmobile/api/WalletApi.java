package com.cybexmobile.api;


import com.cybexmobile.constant.ErrorCode;
import com.cybexmobile.crypto.Sha256Object;
import com.cybexmobile.exception.NetworkStatusException;
import com.cybexmobile.crypto.Aes;
import com.cybexmobile.crypto.Sha512Object;
import com.cybexmobile.faucet.CreateAccountException;
import com.cybexmobile.faucet.CreateAccountObject;
import com.cybexmobile.fc.io.BaseEncoder;
import com.cybexmobile.fc.io.DataStreamEncoder;
import com.cybexmobile.fc.io.DataStreamSizeEncoder;
import com.cybexmobile.fc.io.RawType;
import com.cybexmobile.graphene.chain.AccountObject;
import com.cybexmobile.graphene.chain.Asset;
import com.cybexmobile.graphene.chain.AssetObject;
import com.cybexmobile.graphene.chain.BucketObject;
import com.cybexmobile.graphene.chain.FullAccountObject;
import com.cybexmobile.graphene.chain.GlobalConfigObject;
import com.cybexmobile.graphene.chain.LimitOrderObject;
import com.cybexmobile.graphene.chain.ObjectId;
import com.cybexmobile.graphene.chain.PrivateKey;
import com.cybexmobile.graphene.chain.Types;
import com.cybexmobile.market.MarketTicker;
import com.cybexmobile.market.MarketTrade;
import com.google.common.primitives.UnsignedInteger;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.cybexmobile.constant.ErrorCode.ERROR_NETWORK_FAIL;
import static com.cybexmobile.constant.ErrorCode.ERROR_SERVER_CREATE_ACCOUNT_FAIL;
import static com.cybexmobile.constant.ErrorCode.ERROR_SERVER_RESPONSE_FAIL;

public class WalletApi {


    class wallet_object {
        Sha256Object chain_id;
        List<AccountObject> my_accounts = new ArrayList<>();
        ByteBuffer cipher_keys;
        HashMap<ObjectId<AccountObject>, List<Types.public_key_type>> extra_keys = new HashMap<>();
        String ws_server = "";
        String ws_user = "";
        String ws_password = "";

        public void update_account(AccountObject accountObject) {
            boolean bUpdated = false;
            for (int i = 0; i < my_accounts.size(); ++i) {
                if (my_accounts.get(i).id == accountObject.id) {
                    my_accounts.remove(i);
                    my_accounts.add(accountObject);
                    bUpdated = true;
                    break;
                }
            }

            if (bUpdated == false) {
                my_accounts.add(accountObject);
            }
        }
    }

    private WebSocketApi mWebSocketApi = new WebSocketApi();
    private wallet_object mWalletObject;
    private boolean mbLogin = false;
    private HashMap<Types.public_key_type, Types.private_key_type> mHashMapPub2Priv = new HashMap<>();
    private Sha512Object mCheckSum = new Sha512Object();

    static class plain_keys {
        Map<Types.public_key_type, String> keys;
        Sha512Object checksum;

        public void write_to_encoder(BaseEncoder encoder) {
            RawType rawType = new RawType();

            rawType.pack(encoder, UnsignedInteger.fromIntBits(keys.size()));
            for (Map.Entry<Types.public_key_type, String> entry : keys.entrySet()) {
                encoder.write(entry.getKey().key_data);

                byte[] byteValue = entry.getValue().getBytes();
                rawType.pack(encoder, UnsignedInteger.fromIntBits(byteValue.length));
                encoder.write(byteValue);
            }
            encoder.write(checksum.hash);
        }

        public static plain_keys from_input_stream(InputStream inputStream) {
            plain_keys keysResult = new plain_keys();
            keysResult.keys = new HashMap<>();
            keysResult.checksum = new Sha512Object();

            RawType rawType = new RawType();
            UnsignedInteger size = rawType.unpack(inputStream);
            try {
                for (int i = 0; i < size.longValue(); ++i) {
                    Types.public_key_type publicKeyType = new Types.public_key_type();
                    inputStream.read(publicKeyType.key_data);

                    UnsignedInteger strSize = rawType.unpack(inputStream);
                    byte[] byteBuffer = new byte[strSize.intValue()];
                    inputStream.read(byteBuffer);

                    String strPrivateKey = new String(byteBuffer);

                    keysResult.keys.put(publicKeyType, strPrivateKey);
                }

                inputStream.read(keysResult.checksum.hash);
            } catch (IOException e) {
                e.printStackTrace();
            }

            return keysResult;
        }


    }

    public WalletApi() {

    }

    public int initialize() {
        int nRet = mWebSocketApi.connect();
        if (nRet == 0) {
            Sha256Object sha256Object = null;
            try {
                sha256Object = mWebSocketApi.get_chain_id();
                if (mWalletObject == null) {
                    mWalletObject = new wallet_object();
                    mWalletObject.chain_id = sha256Object;
                } else if (mWalletObject.chain_id != null &&
                        mWalletObject.chain_id.equals(sha256Object) == false) {
                    nRet = -1;
                }
            } catch (NetworkStatusException e) {
                e.printStackTrace();
                nRet = -1;
            }
        }
        return nRet;
    }

    public int reset() {
        mWebSocketApi.close();

        mWalletObject = null;
        mbLogin = false;
        mHashMapPub2Priv.clear();;
        mCheckSum = new Sha512Object();

        return 0;
    }

    public boolean is_locked() {
        if (mWalletObject.cipher_keys.array().length > 0 &&
                mCheckSum.equals(new Sha512Object())) {
            return true;
        }

        return false;
    }

    private void encrypt_keys() {
        plain_keys data = new plain_keys();
        data.keys = new HashMap<>();
        for (Map.Entry<Types.public_key_type, Types.private_key_type> entry : mHashMapPub2Priv.entrySet()) {
            data.keys.put(entry.getKey(), entry.getValue().toString());
        }
        data.checksum = mCheckSum;

        DataStreamSizeEncoder sizeEncoder = new DataStreamSizeEncoder();
        data.write_to_encoder(sizeEncoder);
        DataStreamEncoder encoder = new DataStreamEncoder(sizeEncoder.getSize());
        data.write_to_encoder(encoder);

        byte[] byteKey = new byte[32];
        System.arraycopy(mCheckSum.hash, 0, byteKey, 0, byteKey.length);
        byte[] ivBytes = new byte[16];
        System.arraycopy(mCheckSum.hash, 32, ivBytes, 0, ivBytes.length);

        ByteBuffer byteResult = Aes.encrypt(byteKey, ivBytes, encoder.getData());

        mWalletObject.cipher_keys = byteResult;

        return;

    }

    public int lock() {
        encrypt_keys();

        mCheckSum = new Sha512Object();
        mHashMapPub2Priv.clear();

        return 0;
    }

    public int unlock(String strPassword) {
        assert(strPassword.length() > 0);
        Sha512Object passwordHash = Sha512Object.create_from_string(strPassword);
        byte[] byteKey = new byte[32];
        System.arraycopy(passwordHash.hash, 0, byteKey, 0, byteKey.length);
        byte[] ivBytes = new byte[16];
        System.arraycopy(passwordHash.hash, 32, ivBytes, 0, ivBytes.length);

        ByteBuffer byteDecrypt = Aes.decrypt(byteKey, ivBytes, mWalletObject.cipher_keys.array());
        if (byteDecrypt == null || byteDecrypt.array().length == 0) {
            return -1;
        }

        plain_keys dataResult = plain_keys.from_input_stream(
                new ByteArrayInputStream(byteDecrypt.array())
        );

        for (Map.Entry<Types.public_key_type, String> entry : dataResult.keys.entrySet()) {
            Types.private_key_type privateKeyType = new Types.private_key_type(entry.getValue());
            mHashMapPub2Priv.put(entry.getKey(), privateKeyType);
        }

        mCheckSum = passwordHash;
        if (passwordHash.equals(dataResult.checksum)) {
            return 0;
        } else {
            return -1;
        }
    }

    public boolean is_new() {
        if (mWalletObject == null || mWalletObject.cipher_keys == null) {
            return true;
        }

        return (mWalletObject.cipher_keys.array().length == 0 &&
                mCheckSum.equals(new Sha512Object()));
    }

    public int load_wallet_file(String strFileName) {
        if (mWalletObject != null) {
            return 0;
        }

        try {
            FileInputStream fileInputStream = new FileInputStream(strFileName);
            InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream);
            Gson gson = GlobalConfigObject.getInstance().getGsonBuilder().create();
            mWalletObject = gson.fromJson(inputStreamReader, wallet_object.class);
            return 0;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return -1;
        } catch (JsonSyntaxException e) {
            e.printStackTrace();
            return -1;
        } catch (JsonIOException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public int save_wallet_file(String strFileName) {
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(strFileName);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(fileOutputStream);
            Gson gson = GlobalConfigObject.getInstance().getGsonBuilder().create();

            outputStreamWriter.write(gson.toJson(mWalletObject));
            outputStreamWriter.flush();
            outputStreamWriter.close();
            fileOutputStream.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return 0;
    }

    public int set_password(String strPassword) {
        mCheckSum = Sha512Object.create_from_string(strPassword);

        return 0;
    }

    /*public synchronized int login(String strUserName, String strPassowrd) throws IOException {
        if (mbLogin) {
            return 0;
        }

        if (mWalletObject != null) {
            mbLogin = mWebSocketApi.login(
                    mWalletObject.ws_user,
                    mWalletObject.ws_password
            );
        } else {
            mWalletObject = new wallet_object();
            mbLogin = mWebSocketApi.login(
                    strUserName,
                    strPassowrd
            );
        }

        if (mbLogin) {
            mWebSocketApi.get_database_api_id();
            mWebSocketApi.get_history_api_id();
            mWebSocketApi.get_broadcast_api_id();


            mWalletObject.ws_user = strUserName;
            mWalletObject.ws_password = strPassowrd;
            Sha256Object sha256Object = mWebSocketApi.get_chain_id();
            if (mWalletObject.chain_id != null &&
                    mWalletObject.chain_id.equals(sha256Object) == false) {
                return -1; // 之前的chain_id与当前的chain_id不一致
            }
            mWalletObject.chain_id = sha256Object;
        }

        return 0;
    }*/

    public List<AccountObject> list_my_accounts() {
        List<AccountObject> accountObjectList = new ArrayList<>();
        if (mWalletObject != null) {
            accountObjectList.addAll(mWalletObject.my_accounts);
        }
        return accountObjectList;
    }

    public AccountObject get_account(String strAccountNameOrId) throws NetworkStatusException {
        // 判定这类型
        ObjectId<AccountObject> accountObjectId = ObjectId.create_from_string(strAccountNameOrId);

        List<AccountObject> listAccountObject = null;
        if (accountObjectId == null) {
            return get_account_by_name(strAccountNameOrId);
        } else {
            List<ObjectId<AccountObject>> listAccountObjectId = new ArrayList<>();
            listAccountObjectId.add(accountObjectId);
            listAccountObject = mWebSocketApi.get_accounts(listAccountObjectId);
        }

        if (listAccountObject.isEmpty()) {
            return null;
        }

        return listAccountObject.get(0);
    }

    public List<AccountObject> get_accounts(List<ObjectId<AccountObject>> listAccountObjectId) throws NetworkStatusException {
        return mWebSocketApi.get_accounts(listAccountObjectId);
    }

    public List<AccountObject> lookup_account_names(String strAccountName) throws NetworkStatusException {
        return mWebSocketApi.lookup_account_names(strAccountName);
    }

    public AccountObject get_account_by_name(String strAccountName) throws NetworkStatusException {
        return mWebSocketApi.get_account_by_name(strAccountName);
    }

    public List<Asset> list_account_balance(ObjectId<AccountObject> accountId) throws NetworkStatusException {
        return mWebSocketApi.list_account_balances(accountId);
    }

//    public List<OperationHistoryObject> get_account_history(ObjectId<AccountObject> accountId, int nLimit) throws NetworkStatusException {
//        return mWebSocketApi.get_account_history(accountId, nLimit);
//    }

    public List<AssetObject> list_assets(String strLowerBound, int nLimit) throws NetworkStatusException {
        return mWebSocketApi.list_assets(strLowerBound, nLimit);
    }
    public List<AssetObject> get_assets(List<ObjectId<AssetObject>> listAssetObjectId) throws NetworkStatusException {
        return mWebSocketApi.get_assets(listAssetObjectId);
    }

//    public block_header get_block_header(int nBlockNumber) throws NetworkStatusException {
//        return mWebSocketApi.get_block_header(nBlockNumber);
//    }

    public AssetObject lookup_asset_symbols(String strAssetSymbol) throws NetworkStatusException {
        return mWebSocketApi.lookup_asset_symbols(strAssetSymbol);
    }

    public AssetObject get_objects(String objectId) throws NetworkStatusException {
        return mWebSocketApi.get_object(objectId);
    }

//    public int import_brain_key(String strAccountNameOrId, String strBrainKey) throws NetworkStatusException {
//        AccountObject accountObject = get_account(strAccountNameOrId);
//        if (accountObject == null) {
//            return ErrorCode.ERROR_NO_ACCOUNT_OBJECT;
//        }
//
//        Map<Types.public_key_type, Types.private_key_type> mapPublic2Private = new HashMap<>();
//        for (int i = 0; i < 10; ++i) {
//            BrainKey brainKey = new BrainKey(strBrainKey, i);
//            ECKey ecKey = brainKey.getPrivateKey();
//            PrivateKey privateKey = new PrivateKey(ecKey.getPrivKeyBytes());
//            Types.private_key_type privateKeyType = new Types.private_key_type(privateKey);
//            Types.public_key_type publicKeyType = new Types.public_key_type(privateKey.get_public_key());
//
//            if (accountObject.active.is_public_key_type_exist(publicKeyType) == false &&
//                    accountObject.owner.is_public_key_type_exist(publicKeyType) == false &&
//                    accountObject.options.memo_key.compare(publicKeyType) == false) {
//                continue;
//            }
//            mapPublic2Private.put(publicKeyType, privateKeyType);
//        }
//
//        if (mapPublic2Private.isEmpty() == true) {
//            return ErrorCode.ERROR_IMPORT_NOT_MATCH_PRIVATE_KEY;
//        }
//
//        mWalletObject.update_account(accountObject);
//
//        List<Types.public_key_type> listPublicKeyType = new ArrayList<>();
//        listPublicKeyType.addAll(mapPublic2Private.keySet());
//
//        mWalletObject.extra_keys.put(accountObject.id, listPublicKeyType);
//        mHashMapPub2Priv.putAll(mapPublic2Private);
//
//        encrypt_keys();
//
//        return 0;
//    }

//    public int import_key(String account_name_or_id,
//                          String wif_key) throws NetworkStatusException {
//        assert (is_locked() == false && is_new() == false);
//
//        Types.private_key_type privateKeyType = new Types.private_key_type(wif_key);
//
//        PublicKey publicKey = privateKeyType.getPrivateKey().get_public_key();
//        Types.public_key_type publicKeyType = new Types.public_key_type(publicKey);
//
//        AccountObject accountObject = get_account(account_name_or_id);
//        if (accountObject == null) {
//            return ErrorCode.ERROR_NO_ACCOUNT_OBJECT;
//        }
//
//        /*List<AccountObject> listAccountObject = lookup_account_names(account_name_or_id);
//        // 进行publicKey的比对
//        if (listAccountObject.isEmpty()) {
//            return -1;
//        }
//
//        AccountObject accountObject = listAccountObject.get(0);*/
//        if (accountObject.active.is_public_key_type_exist(publicKeyType) == false &&
//                accountObject.owner.is_public_key_type_exist(publicKeyType) == false &&
//                accountObject.options.memo_key.compare(publicKeyType) == false) {
//            return -1;
//        }
//
//        mWalletObject.update_account(accountObject);
//
//        List<Types.public_key_type> listPublicKeyType = new ArrayList<>();
//        listPublicKeyType.add(publicKeyType);
//
//        mWalletObject.extra_keys.put(accountObject.id, listPublicKeyType);
//        mHashMapPub2Priv.put(publicKeyType, privateKeyType);
//
//        encrypt_keys();
//
//        // 保存至文件
//
//        return 0;
//    }

//    public int import_keys(String account_name_or_id,
//                           String wif_key_1,
//                           String wif_key_2) throws NetworkStatusException {
//        assert (is_locked() == false && is_new() == false);
//
//        Types.private_key_type privateKeyType1 = new Types.private_key_type(wif_key_1);
//        Types.private_key_type privateKeyType2 = new Types.private_key_type(wif_key_2);
//
//        PublicKey publicKey1 = privateKeyType1.getPrivateKey().get_public_key();
//        PublicKey publicKey2 = privateKeyType1.getPrivateKey().get_public_key();
//        Types.public_key_type publicKeyType1 = new Types.public_key_type(publicKey1);
//        Types.public_key_type publicKeyType2 = new Types.public_key_type(publicKey2);
//
//        AccountObject accountObject = get_account(account_name_or_id);
//        if (accountObject == null) {
//            return ErrorCode.ERROR_NO_ACCOUNT_OBJECT;
//        }
//
//        /*List<AccountObject> listAccountObject = lookup_account_names(account_name_or_id);
//        // 进行publicKey的比对
//        if (listAccountObject.isEmpty()) {
//            return -1;
//        }
//
//        AccountObject accountObject = listAccountObject.get(0);*/
//        if (accountObject.active.is_public_key_type_exist(publicKeyType1) == false &&
//                accountObject.owner.is_public_key_type_exist(publicKeyType1) == false &&
//                accountObject.options.memo_key.compare(publicKeyType1) == false) {
//            return -1;
//        }
//
//        if (accountObject.active.is_public_key_type_exist(publicKeyType2) == false &&
//                accountObject.owner.is_public_key_type_exist(publicKeyType2) == false &&
//                accountObject.options.memo_key.compare(publicKeyType2) == false) {
//            return -1;
//        }
//
//
//
//        mWalletObject.update_account(accountObject);
//
//        List<Types.public_key_type> listPublicKeyType = new ArrayList<>();
//        listPublicKeyType.add(publicKeyType1);
//        listPublicKeyType.add(publicKeyType2);
//
//        mWalletObject.extra_keys.put(accountObject.id, listPublicKeyType);
//        mHashMapPub2Priv.put(publicKeyType1, privateKeyType1);
//        mHashMapPub2Priv.put(publicKeyType2, privateKeyType2);
//
//        encrypt_keys();
//
//        // 保存至文件
//        return 0;
//    }

    public int import_account_password(String strAccountName,
                                       String strPassword) throws NetworkStatusException {
        PrivateKey privateActiveKey = PrivateKey.from_seed(strAccountName + "active" + strPassword);
        PrivateKey privateOwnerKey = PrivateKey.from_seed(strAccountName + "owner" + strPassword);
        PrivateKey privateMemoKey = PrivateKey.from_seed(strAccountName + "memo" + strPassword);

        Types.public_key_type publicActiveKeyType = new Types.public_key_type(privateActiveKey.get_public_key());
        Types.public_key_type publicOwnerKeyType = new Types.public_key_type(privateOwnerKey.get_public_key());
        Types.public_key_type publicMemoKeyTyep = new Types.public_key_type(privateMemoKey.get_public_key());

        AccountObject accountObject = get_account(strAccountName);
        if (accountObject == null) {
            return ErrorCode.ERROR_NO_ACCOUNT_OBJECT;
        }

        if (accountObject.active.is_public_key_type_exist(publicActiveKeyType) == false &&
                accountObject.active.is_public_key_type_exist(publicOwnerKeyType) == false &&
                accountObject.owner.is_public_key_type_exist(publicActiveKeyType) == false &&
                accountObject.owner.is_public_key_type_exist(publicOwnerKeyType) == false){
            return ErrorCode.ERROR_PASSWORD_INVALID;
        }

        List<Types.public_key_type> listPublicKeyType = new ArrayList<>();
        listPublicKeyType.add(publicActiveKeyType);
        listPublicKeyType.add(publicOwnerKeyType);
        mWalletObject.update_account(accountObject);
        mWalletObject.extra_keys.put(accountObject.id, listPublicKeyType);
        mHashMapPub2Priv.put(publicActiveKeyType, new Types.private_key_type(privateActiveKey));
        mHashMapPub2Priv.put(publicOwnerKeyType, new Types.private_key_type(privateOwnerKey));

        encrypt_keys();

        return 0;
    }

//    public Asset transfer_calculate_fee(String strAmount,
//                                        String strAssetSymbol,
//                                        String strMemo) throws NetworkStatusException {
//        ObjectId<AssetObject> assetObjectId = ObjectId.create_from_string(strAssetSymbol);
//        AssetObject assetObject = null;
//        if (assetObjectId == null) {
//            assetObject = lookup_asset_symbols(strAssetSymbol);
//        } else {
//            List<ObjectId<AssetObject>> listAssetObjectId = new ArrayList<>();
//            listAssetObjectId.add(assetObjectId);
//            assetObject = get_assets(listAssetObjectId).get(0);
//        }
//
//        operations.transfer_operation transferOperation = new operations.transfer_operation();
//        transferOperation.from = new ObjectId<AccountObject>(0, AccountObject.class);//accountObjectFrom.id;
//        transferOperation.to = new ObjectId<AccountObject>(0, AccountObject.class);
//        transferOperation.amount = assetObject.amount_from_string(strAmount);
//        transferOperation.extensions = new HashSet<>();
//        /*if (TextUtils.isEmpty(strMemo) == false) {
//
//        }*/
//
//        operations.operation_type operationType = new operations.operation_type();
//        operationType.nOperationType = 0;
//        operationType.operationContent = transferOperation;
//
//        signed_transaction tx = new signed_transaction();
//        tx.operations = new ArrayList<>();
//        tx.operations.add(operationType);
//        tx.extensions = new HashSet<>();
//        set_operation_fees(tx, get_global_properties().parameters.current_fees);
//
//        return transferOperation.fee;
//    }

//    public signed_transaction transfer(String strFrom,
//                                       String strTo,
//                                       String strAmount,
//                                       String strAssetSymbol,
//                                       String strMemo) throws NetworkStatusException {
//
//        ObjectId<AssetObject> assetObjectId = ObjectId.create_from_string(strAssetSymbol);
//        AssetObject assetObject = null;
//        if (assetObjectId == null) {
//            assetObject = lookup_asset_symbols(strAssetSymbol);
//        } else {
//            List<ObjectId<AssetObject>> listAssetObjectId = new ArrayList<>();
//            listAssetObjectId.add(assetObjectId);
//            assetObject = get_assets(listAssetObjectId).get(0);
//        }
//
//        AccountObject accountObjectFrom = get_account(strFrom);
//        AccountObject accountObjectTo = get_account(strTo);
//        if (accountObjectTo == null) {
//            return null;
//        }
//
//        operations.transfer_operation transferOperation = new operations.transfer_operation();
//        transferOperation.from = accountObjectFrom.id;
//        transferOperation.to = accountObjectTo.id;
//        transferOperation.amount = assetObject.amount_from_string(strAmount);
//        transferOperation.extensions = new HashSet<>();
//        if (TextUtils.isEmpty(strMemo) == false) {
//            transferOperation.memo = new memo_data();
//            transferOperation.memo.from = accountObjectFrom.options.memo_key;
//            transferOperation.memo.to = accountObjectTo.options.memo_key;
//
//            Types.private_key_type privateKeyType = mHashMapPub2Priv.get(accountObjectFrom.options.memo_key);
//            if (privateKeyType == null) {
//                // // TODO: 07/09/2017 获取失败的问题
//            }
//            transferOperation.memo.set_message(
//                    privateKeyType.getPrivateKey(),
//                    accountObjectTo.options.memo_key.getPublicKey(),
//                    strMemo,
//                    0
//            );
//            transferOperation.memo.get_message(
//                    privateKeyType.getPrivateKey(),
//                    accountObjectTo.options.memo_key.getPublicKey()
//            );
//        }
//
//        operations.operation_type operationType = new operations.operation_type();
//        operationType.nOperationType = operations.ID_TRANSER_OPERATION;
//        operationType.operationContent = transferOperation;
//
//        signed_transaction tx = new signed_transaction();
//        tx.operations = new ArrayList<>();
//        tx.operations.add(operationType);
//        tx.extensions = new HashSet<>();
//        set_operation_fees(tx, get_global_properties().parameters.current_fees);
//
//
//        //// TODO: 07/09/2017 tx.validate();
//        return sign_transaction(tx);
//    }

//    public Asset calculate_sell_asset_fee(String amountToSell, AssetObject assetToSell,
//                                          String minToReceive, AssetObject assetToReceive,
//                                          global_property_object globalPropertyObject) {
//        operations.limit_order_create_operation op = new operations.limit_order_create_operation();
//        op.amount_to_sell = assetToSell.amount_from_string(amountToSell);
//        op.min_to_receive = assetToReceive.amount_from_string(minToReceive);
//
//        operations.operation_type operationType = new operations.operation_type();
//        operationType.nOperationType = operations.ID_CREATE_LIMIT_ORDER_OPERATION;
//        operationType.operationContent = op;
//
//        signed_transaction tx = new signed_transaction();
//        tx.operations = new ArrayList<>();
//        tx.operations.add(operationType);
//
//        tx.extensions = new HashSet<>();
//        set_operation_fees(tx, globalPropertyObject.parameters.current_fees);
//
//        return op.fee;
//    }

//    public Asset calculate_sell_fee(AssetObject assetToSell, AssetObject assetToReceive,
//                                    double rate, double amount,
//                                    global_property_object globalPropertyObject) {
//        return calculate_sell_asset_fee(Double.toString(amount), assetToSell,
//                Double.toString(rate * amount), assetToReceive, globalPropertyObject);
//    }

//    public Asset calculate_buy_fee(AssetObject assetToReceive, AssetObject assetToSell,
//                                   double rate, double amount,
//                                   global_property_object globalPropertyObject) {
//        return calculate_sell_asset_fee(Double.toString(rate * amount), assetToSell,
//                Double.toString(amount), assetToReceive, globalPropertyObject);
//    }

//    public signed_transaction sell_asset(String amountToSell, String symbolToSell,
//                                         String minToReceive, String symbolToReceive,
//                                         int timeoutSecs, boolean fillOrKill)
//            throws NetworkStatusException {
//        // 这是用于出售的帐号
//        AccountObject accountObject = list_my_accounts().get(0);
//        operations.limit_order_create_operation op = new operations.limit_order_create_operation();
//        op.seller = accountObject.id;
//
//        // 填充数据
//        op.amount_to_sell = lookup_asset_symbols(symbolToSell).amount_from_string(amountToSell);
//        op.min_to_receive = lookup_asset_symbols(symbolToReceive).amount_from_string(minToReceive);
//        if (timeoutSecs > 0) {
//            op.expiration = new Date(
//                    System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSecs));
//        } else {
//            op.expiration = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365));
//        }
//        op.fill_or_kill = fillOrKill;
//        op.extensions = new HashSet<>();
//
//        operations.operation_type operationType = new operations.operation_type();
//        operationType.nOperationType = operations.ID_CREATE_LIMIT_ORDER_OPERATION;
//        operationType.operationContent = op;
//
//        signed_transaction tx = new signed_transaction();
//        tx.operations = new ArrayList<>();
//        tx.operations.add(operationType);
//
//        tx.extensions = new HashSet<>();
//        set_operation_fees(tx, get_global_properties().parameters.current_fees);
//
//        return sign_transaction(tx);
//    }

//    /**
//     * @param symbolToSell 卖出的货币符号
//     * @param symbolToReceive 买入的货币符号
//     * @param rate 多少个<t>symbolToReceive</t>可以兑换一个<t>symbolToSell</t>
//     * @param amount 要卖出多少个<t>symbolToSell</t>
//     * @throws NetworkStatusException
//     */
//    public signed_transaction sell(String symbolToSell, String symbolToReceive, double rate,
//                                   double amount) throws NetworkStatusException {
//        return sell_asset(Double.toString(amount), symbolToSell, Double.toString(rate * amount),
//                symbolToReceive, 0, false);
//    }
//
//    public signed_transaction sell(String symbolToSell, String symbolToReceive, double rate,
//                                   double amount, int timeoutSecs) throws NetworkStatusException {
//        return sell_asset(Double.toString(amount), symbolToSell, Double.toString(rate * amount),
//                symbolToReceive, timeoutSecs, false);
//    }

//    /**
//     * @param symbolToReceive 买入的货币符号
//     * @param symbolToSell 卖出的货币符号
//     * @param rate 多少个<t>symbolToSell</t>可以兑换一个<t>symbolToReceive</t>
//     * @param amount 要买入多少个<t>symbolToReceive</t>
//     * @throws NetworkStatusException
//     */
//    public signed_transaction buy(String symbolToReceive, String symbolToSell, double rate,
//                                  double amount) throws NetworkStatusException {
//        return sell_asset(Double.toString(rate * amount), symbolToSell, Double.toString(amount),
//                symbolToReceive, 0, false);
//    }

//    public signed_transaction buy(String symbolToReceive, String symbolToSell, double rate,
//                                  double amount, int timeoutSecs) throws NetworkStatusException {
//        return sell_asset(Double.toString(rate * amount), symbolToSell, Double.toString(amount),
//                symbolToReceive, timeoutSecs, false);
//    }

//    public signed_transaction cancel_order(ObjectId<LimitOrderObject> id)
//            throws NetworkStatusException {
//        operations.limit_order_cancel_operation op = new operations.limit_order_cancel_operation();
//        op.fee_paying_account = mWebSocketApi.get_limit_order(id).seller;
//        op.order = id;
//        op.extensions = new HashSet<>();
//
//        operations.operation_type operationType = new operations.operation_type();
//        operationType.nOperationType = operations.ID_CANCEL_LMMIT_ORDER_OPERATION;
//        operationType.operationContent = op;
//
//        signed_transaction tx = new signed_transaction();
//        tx.operations = new ArrayList<>();
//        tx.operations.add(operationType);
//
//        tx.extensions = new HashSet<>();
//        set_operation_fees(tx, get_global_properties().parameters.current_fees);
//
//        return sign_transaction(tx);
//    }

//    public global_property_object get_global_properties() throws NetworkStatusException {
//        return mWebSocketApi.get_global_properties();
//    }

//    public dynamic_global_property_object get_dynamic_global_properties() throws NetworkStatusException {
//        return mWebSocketApi.get_dynamic_global_properties();
//    }

//    public void create_account_with_private_key(PrivateKey privateOwnerKey,
//                                                String strAccountName,
//                                                String strPassword,
//                                                String strRegistar,
//                                                String strReferrer) throws NetworkStatusException {
//        int nActiveKeyIndex = find_first_unused_derived_key_index(privateOwnerKey);
//
//        String strWifKey = new Types.private_key_type(privateOwnerKey).toString();
//        PrivateKey privateActiveKey = derive_private_key(strWifKey, nActiveKeyIndex);
//
//        strWifKey = new Types.private_key_type(privateActiveKey).toString();
//        int nMemoKeyIndex = find_first_unused_derived_key_index(privateActiveKey);
//        PrivateKey privateMemoKey = derive_private_key(strWifKey, nMemoKeyIndex);
//
//        Types.public_key_type publicOwnerKey = new Types.public_key_type(privateOwnerKey.get_public_key());
//        Types.public_key_type publicActiveKey = new Types.public_key_type(privateActiveKey.get_public_key());
//        Types.public_key_type publicMemoKey = new Types.public_key_type(privateMemoKey.get_public_key());
//
//        operations.account_create_operation operation = new operations.account_create_operation();
//        operation.name = strAccountName;
//        operation.options.memo_key = publicMemoKey;
//        operation.active = new Authority(1, publicActiveKey, 1);
//        operation.owner = new Authority(1, publicOwnerKey, 1);
//
//        AccountObject accountRegistrar = get_account(strRegistar);
//        AccountObject accountReferr = get_account(strReferrer);
//
//        operation.referrer = accountReferr.id;
//        operation.registrar = accountRegistrar.id;
//        operation.referrer_percent = accountReferr.referrer_rewards_percentage;
//
//        global_property_object globalPropertyObject = mWebSocketApi.get_global_properties();
//        dynamic_global_property_object dynamicGlobalPropertyObject = mWebSocketApi.get_dynamic_global_properties();
//
//    }

//    private signed_transaction sign_transaction(signed_transaction tx) throws NetworkStatusException {
//        // // TODO: 07/09/2017 这里的set应出问题
//        signed_transaction.required_authorities requiresAuthorities = tx.get_required_authorities();
//
//        Set<ObjectId<AccountObject>> req_active_approvals = new HashSet<>();
//        req_active_approvals.addAll(requiresAuthorities.active);
//
//        Set<ObjectId<AccountObject>> req_owner_approvals = new HashSet<>();
//        req_owner_approvals.addAll(requiresAuthorities.owner);
//
//
//        for (Authority authorityObject : requiresAuthorities.other) {
//            for (ObjectId<AccountObject> accountObjectId : authorityObject.account_auths.keySet()) {
//                req_active_approvals.add(accountObjectId);
//            }
//        }
//
//        Set<ObjectId<AccountObject>> accountObjectAll = new HashSet<>();
//        accountObjectAll.addAll(req_active_approvals);
//        accountObjectAll.addAll(req_owner_approvals);
//
//
//        List<ObjectId<AccountObject>> listAccountObjectId = new ArrayList<>();
//        listAccountObjectId.addAll(accountObjectAll);
//
//        List<AccountObject> listAccountObject = get_accounts(listAccountObjectId);
//        HashMap<ObjectId<AccountObject>, AccountObject> hashMapIdToObject = new HashMap<>();
//        for (AccountObject accountObject : listAccountObject) {
//            hashMapIdToObject.put(accountObject.id, accountObject);
//        }
//
//        HashSet<Types.public_key_type> approving_key_set = new HashSet<>();
//        for (ObjectId<AccountObject> accountObjectId : req_active_approvals) {
//            AccountObject accountObject = hashMapIdToObject.get(accountObjectId);
//            approving_key_set.addAll(accountObject.active.get_keys());
//        }
//
//        for (ObjectId<AccountObject> accountObjectId : req_owner_approvals) {
//            AccountObject accountObject = hashMapIdToObject.get(accountObjectId);
//            approving_key_set.addAll(accountObject.owner.get_keys());
//        }
//
//        for (Authority authorityObject : requiresAuthorities.other) {
//            for (Types.public_key_type publicKeyType : authorityObject.get_keys()) {
//                approving_key_set.add(publicKeyType);
//            }
//        }
//
//        // // TODO: 07/09/2017 被简化了
//        dynamic_global_property_object dynamicGlobalPropertyObject = get_dynamic_global_properties();
//        tx.set_reference_block(dynamicGlobalPropertyObject.head_block_id);
//
//        Date dateObject = dynamicGlobalPropertyObject.time;
//        Calendar calender = Calendar.getInstance();
//        calender.setTime(dateObject);
//        calender.add(Calendar.SECOND, 30);
//
//        dateObject = calender.getTime();
//
//        tx.set_expiration(dateObject);
//
//        for (Types.public_key_type pulicKeyType : approving_key_set) {
//            Types.private_key_type privateKey = mHashMapPub2Priv.get(pulicKeyType);
//            if (privateKey != null) {
//                tx.sign(privateKey, mWalletObject.chain_id);
//            }
//        }
//
//        // 发出tx，进行广播，这里也涉及到序列化
//        int nRet = mWebSocketApi.broadcast_transaction(tx);
//        if (nRet == 0) {
//            return tx;
//        } else {
//            return null;
//        }
//    }

    public List<BucketObject> get_market_history(ObjectId<AssetObject> assetObjectId1,
                                                 ObjectId<AssetObject> assetObjectId2,
                                                 int nBucket,
                                                 Date dateStart,
                                                 Date dateEnd) throws NetworkStatusException {
        return mWebSocketApi.get_market_history(
                assetObjectId1,
                assetObjectId2,
                nBucket,
                dateStart,
                dateEnd
        );
    }

//    private void set_operation_fees(signed_transaction tx, fee_schedule feeSchedule) {
//        for (operations.operation_type operationType : tx.operations) {
//            feeSchedule.set_fee(operationType, Price.unit_price(new ObjectId<AssetObject>(0, AssetObject.class)));
//        }
//    }


//    private PrivateKey derive_private_key(String strWifKey, int nSeqNumber) {
//        String strData = strWifKey + " " + nSeqNumber;
//        byte[] bytesBuffer = strData.getBytes();
//        SHA512Digest digest = new SHA512Digest();
//        digest.update(bytesBuffer, 0, bytesBuffer.length);
//
//        byte[] out = new byte[64];
//        digest.doFinal(out, 0);
//
//        SHA256Digest digest256 = new SHA256Digest();
//        byte[] outSeed = new byte[32];
//        digest256.update(out, 0, out.length);
//        digest.doFinal(outSeed, 0);
//
//        return new PrivateKey(outSeed);
//    }

//    private int find_first_unused_derived_key_index(PrivateKey privateKey) {
//        int first_unused_index = 0;
//        int number_of_consecutive_unused_keys = 0;
//
//        String strWifKey = new Types.private_key_type(privateKey).toString();
//        for (int key_index = 0; ; ++key_index) {
//            PrivateKey derivedPrivateKey = derive_private_key(strWifKey, key_index);
//            Types.public_key_type publicKeyType = new Types.public_key_type(derivedPrivateKey.get_public_key());
//
//            if (mHashMapPub2Priv.containsKey(publicKeyType) == false) {
//                if (number_of_consecutive_unused_keys != 0)
//                {
//                    ++number_of_consecutive_unused_keys;
//                    if (number_of_consecutive_unused_keys > 5)
//                        return first_unused_index;
//                }
//                else
//                {
//                    first_unused_index = key_index;
//                    number_of_consecutive_unused_keys = 1;
//                }
//            } else {
//                first_unused_index = 0;
//                number_of_consecutive_unused_keys = 0;
//            }
//        }
//    }

//    public String decrypt_memo_message(memo_data memoData) {
//        assert(is_locked() == false);
//        String strMessage = null;
//
//        Types.private_key_type privateKeyType = mHashMapPub2Priv.get(memoData.to);
//        if (privateKeyType != null) {
//            strMessage = memoData.get_message(privateKeyType.getPrivateKey(), memoData.from.getPublicKey());
//        } else {
//            privateKeyType = mHashMapPub2Priv.get(memoData.from);
//            if (privateKeyType != null) {
//                strMessage = memoData.get_message(privateKeyType.getPrivateKey(), memoData.to.getPublicKey());
//            }
//        }
//
//        return strMessage;
//    }

    public int create_account_with_password(String strAccountName,
                                            String strPassword,
                                            String pinCode,
                                            String capId) throws NetworkStatusException, CreateAccountException {
        String[] strAddress = {"https://faucet.cybex.io/register"};

        int nRet = -1;
        for (int i = 0; i < strAddress.length; ++i) {
            try {
                nRet = create_account_with_password(
                        strAddress[i],
                        strAccountName,
                        strPassword,
                        pinCode,
                        capId
                );
                if (nRet == 0) {
                    break;
                }
            } catch (NetworkStatusException e) {
                e.printStackTrace();
                if (i == strAddress.length - 1) {
                    throw e;
                }
            } catch (CreateAccountException e) {
                e.printStackTrace();
                if (i == strAddress.length - 1) {
                    throw e;
                }
            }
        }
        return nRet;
    }

    public String subscribe_to_market(String base, String quote) throws NetworkStatusException {
        return mWebSocketApi.subscribe_to_market(base, quote);
    }

    public void set_subscribe_market(boolean filter) throws NetworkStatusException {
        mWebSocketApi.set_subscribe_callback(filter);
    }

    public MarketTicker get_ticker(String base, String quote) throws NetworkStatusException {
        return mWebSocketApi.get_ticker(base, quote);
    }

    public List<MarketTrade> get_trade_history(String base, String quote, Date start, Date end, int limit)
            throws NetworkStatusException {
        return mWebSocketApi.get_trade_history(base, quote, start, end, limit);
    }

    public List<HashMap<String, Object>> get_fill_order_history(ObjectId<AssetObject> base,
                                                                ObjectId<AssetObject> quote,
                                                                int limit) throws NetworkStatusException {
        return mWebSocketApi.get_fill_order_history(base, quote,limit);
    }

    public List<LimitOrderObject> get_limit_orders(ObjectId<AssetObject> base,
                                                   ObjectId<AssetObject> quote,
                                                   int limit) throws NetworkStatusException {
        return mWebSocketApi.get_limit_orders(base, quote, limit);
    }

    public List<FullAccountObject> get_full_accounts(List<String> names, boolean subscribe)
            throws NetworkStatusException {
        return mWebSocketApi.get_full_accounts(names, subscribe);
    }

    public int create_account_with_password(String strServerUrl,
                                            String strAccountName,
                                            String strPassword,
                                            String pinCode,
                                            String capId) throws NetworkStatusException, CreateAccountException {
        PrivateKey privateActiveKey = PrivateKey.from_seed(strAccountName + "active" + strPassword);
        PrivateKey privateOwnerKey = PrivateKey.from_seed(strAccountName + "owner" + strPassword);

        Types.public_key_type publicActiveKeyType = new Types.public_key_type(privateActiveKey.get_public_key());
        Types.public_key_type publicOwnerKeyType = new Types.public_key_type(privateOwnerKey.get_public_key());

        AccountObject accountObject = get_account(strAccountName);
        if (accountObject != null) {
            return ErrorCode.ERROR_ACCOUNT_OBJECT_EXIST;
        }

        CreateAccountObject createAccountObject = new CreateAccountObject();
        CreateAccountObject.cpa cpaObject = new CreateAccountObject.cpa();
        cpaObject.id = capId;
        cpaObject.captcha = pinCode;
        createAccountObject.name = strAccountName;
        createAccountObject.active_key = publicActiveKeyType;
        createAccountObject.owner_key = publicOwnerKeyType;
        createAccountObject.memo_key = publicActiveKeyType;
        createAccountObject.refcode = null;
        createAccountObject.referrer = null;
        Gson gson = GlobalConfigObject.getInstance().getGsonBuilder().create();

        String strAddress = strServerUrl;
        OkHttpClient okHttpClient = new OkHttpClient();

        RequestBody requestBody = RequestBody.create(
                MediaType.parse("application/json"),
                gson.toJson(createAccountObject)
        );

        Request request = new Request.Builder()
                .url(strAddress)
                .addHeader("Accept", "application/json")
                .post(requestBody)
                .build();

        CreateAccountObject.create_account_response createAccountResponse = null;
        try {
            Response response = okHttpClient.newCall(request).execute();
            if (response.isSuccessful()) {
                createAccountResponse = gson.fromJson(
                        response.body().string(),
                        CreateAccountObject.create_account_response.class
                );
            } else {
                if (response.body().contentLength() != 0) {
                    String strResponse = response.body().string();

                    try {
                        CreateAccountObject.response_fail_error error = gson.fromJson(
                                strResponse,
                                CreateAccountObject.response_fail_error.class
                        );
                        for (Map.Entry<String, List<String>> errorEntrySet : error.error.entrySet()) {
                            throw new CreateAccountException(errorEntrySet.getValue().get(0));
                        }
                    } catch (JsonSyntaxException e) {  // 解析失败，直接抛出原有的内容
                        throw new CreateAccountException(strResponse);
                    }
                }

                return ERROR_SERVER_RESPONSE_FAIL;
            }
        } catch (IOException e) {
            e.printStackTrace();

            return ERROR_NETWORK_FAIL;
        }

        if (createAccountResponse.account != null) {
            return 0;
        } else {
            if (createAccountResponse.error.base.isEmpty() == false) {
                String strError = createAccountResponse.error.base.get(0);
                throw new CreateAccountException(strError);
            }
            return ERROR_SERVER_CREATE_ACCOUNT_FAIL;
        }
    }
}
