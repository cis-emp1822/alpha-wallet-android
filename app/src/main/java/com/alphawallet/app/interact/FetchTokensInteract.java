package com.alphawallet.app.interact;

import android.text.TextUtils;

import com.alphawallet.app.entity.ContractLocator;
import com.alphawallet.app.entity.ContractType;
import com.alphawallet.app.entity.NetworkInfo;
import com.alphawallet.app.entity.OrderContractAddressPair;
import com.alphawallet.app.entity.Wallet;
import com.alphawallet.app.entity.opensea.Asset;
import com.alphawallet.app.entity.tokens.ERC721Ticket;
import com.alphawallet.app.entity.tokens.ERC721Token;
import com.alphawallet.app.entity.tokens.Token;
import com.alphawallet.app.entity.tokens.TokenInfo;
import com.alphawallet.app.entity.tokens.TokenTicker;
import com.alphawallet.app.repository.TokenRepositoryType;
import com.alphawallet.app.service.TokensService;
import com.alphawallet.token.entity.MagicLinkData;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class FetchTokensInteract {

    private final TokenRepositoryType tokenRepository;

    public FetchTokensInteract(TokenRepositoryType tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    public Observable<Token> fetchStoredToken(NetworkInfo network, Wallet wallet, String tokenAddress) {
        if (TextUtils.isEmpty(tokenAddress)) tokenAddress = wallet.address.toLowerCase();
        return tokenRepository.fetchCachedSingleToken(network.chainId, wallet.address, tokenAddress)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public Observable<Token[]> fetchStoredWithEth(Wallet wallet) {
        return tokenRepository.fetchActiveStoredPlusEth(wallet.address)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public Single<Token> fetchSingle(Wallet wallet, Token token) {
        return tokenRepository.fetchActiveSingle(wallet.address, token)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public Single<Token[]> storeTickers(Wallet wallet, Token[] tokens) {
        return tokenRepository.storeTickers(wallet, tokens)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public Disposable updateBlockRead(Token token, Wallet wallet)
    {
        return tokenRepository.updateBlockRead(token, wallet);
    }

    public Observable<Token> fetchEth(NetworkInfo network, Wallet wallet)
    {
        return tokenRepository.getEthBalance(network, wallet).toObservable()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public Single<BigInteger> getLatestBlock(int chainId)
    {
        return tokenRepository.fetchLatestBlockNumber(chainId);
    }

    public Observable<ContractLocator> getContractResponse(String address, int chainId, String method)
    {
        return tokenRepository.getTokenResponse(address, chainId, method).toObservable();
    }

    public Observable<Token> updateDefaultBalance(Token token, Wallet wallet)
    {
        return tokenRepository.fetchActiveTokenBalance(wallet.address, token)
                .subscribeOn(Schedulers.io());
    }

    public Observable<OrderContractAddressPair> updateBalancePair(Token token, MagicLinkData order)
    {
        return tokenRepository.fetchActiveTokenBalance(order.ownerAddress, token)
                .map(updateToken -> mapToPair(updateToken, order))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public Observable<Token> updateBalance(String address, Token token)
    {
        if (token == null) return Observable.fromCallable(()
                                      -> new Token(null, BigDecimal.ZERO, 0, "", ContractType.NOT_SET));
        return tokenRepository.fetchActiveTokenBalance(address, token)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public Single<TokenTicker> getEthereumTicker(int chainId)
    {
        return tokenRepository.getEthTicker(chainId);
    }

    private OrderContractAddressPair mapToPair(Token token, MagicLinkData so)
    {
        OrderContractAddressPair pair = new OrderContractAddressPair();
        pair.order = so;
        pair.balance = token.getArrayBalance();
        return pair;
    }

    // Only for sensing ERC721 Ticket
    public Single<Token[]> checkInterface(Token[] tokens, Wallet wallet)
    {
        return Single.fromCallable(() -> {
            //check if the token interface has been checked
            for (int i = 0; i < tokens.length; i++)
            {
                Token t = tokens[i];
                if (t.getInterfaceSpec() == ContractType.ERC721_UNDETERMINED || !t.checkBalanceType()) //balance type appears to be wrong
                {
                    ContractType type = tokenRepository.determineCommonType(t.tokenInfo).blockingGet();
                    TokenInfo tInfo = t.tokenInfo;
                    //upgrade type:
                    switch (type)
                    {
                        case OTHER:
                            //couldn't determine the type, try again next time
                            continue;
                        default:
                            type = ContractType.ERC721;
                        case ERC721:
                        case ERC721_LEGACY:
                            List<Asset> erc721Balance = t.getTokenAssets(); //add balance from Opensea
                            if (TextUtils.isEmpty(tInfo.name + tInfo.symbol)) tInfo = new TokenInfo(tInfo.address, " ", " ", tInfo.decimals, tInfo.isEnabled, tInfo.chainId); //ensure we don't keep overwriting this
                            t = new ERC721Token(tInfo, erc721Balance, System.currentTimeMillis(), t.getNetworkName(), type);
                            tokens[i] = t;
                            break;
                        case ERC721_TICKET:
                            List<BigInteger> balanceFromOpenSea = t.getArrayBalance();
                            t = new ERC721Ticket(t.tokenInfo, balanceFromOpenSea, System.currentTimeMillis(), t.getNetworkName(), ContractType.ERC721_TICKET);
                            tokens[i] = t;
                            break;
                    }

                    //update in database
                    tokenRepository.updateTokenType(t, wallet, type);
                    TokensService.setInterfaceSpec(t.tokenInfo.chainId, t.tokenInfo.address, type);
                }
            }

            return tokens;
        });
    }

    public Single<Boolean> checkRedeemed(Token token, List<BigInteger> tickets)
    {
        if (token == null || tickets == null || tickets.size() == 0) return Single.fromCallable(() -> true ); //early return for invalid input
        BigInteger tokenId = tickets.get(0);
        return tokenRepository.fetchIsRedeemed(token, tokenId);
    }
}
