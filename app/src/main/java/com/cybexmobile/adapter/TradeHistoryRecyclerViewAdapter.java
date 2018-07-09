package com.cybexmobile.adapter;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.cybexmobile.fragment.dummy.DummyContent.DummyItem;
import com.cybexmobile.R;
import com.cybexmobile.market.MarketTrade;
import com.cybexmobile.utils.AssetUtil;
import com.cybexmobile.utils.MyUtils;

import java.util.List;
import java.util.Locale;

/**
 * {@link RecyclerView.Adapter} that can display a {@link DummyItem} and makes a call to the
 * TODO: Replace the implementation with code for your data type.
 */
public class TradeHistoryRecyclerViewAdapter extends RecyclerView.Adapter<TradeHistoryRecyclerViewAdapter.ViewHolder> {

    private final List<MarketTrade> mValues;
    private final Context mContext;
    private int mBasePrecision;
    private int mQuotePrecision;

    public TradeHistoryRecyclerViewAdapter(List<MarketTrade> items, int basePrecision, int quotePrecision, Context context) {
        mValues = items;
        mContext = context;
        mBasePrecision = basePrecision;
        mQuotePrecision = quotePrecision;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_trade_history, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        holder.mItem = mValues.get(position);
        String basePrecisionFormatter = MyUtils.getPrecisedFormatter(mBasePrecision);
        String quotePrecisionFormatter = MyUtils.getPrecisedFormatter(mQuotePrecision);
        /**
         * fix bug:CYM-379
         * 精度不对
         */
        holder.mPriceView.setText(String.format(Locale.US, AssetUtil.formatPrice(mValues.get(position).price), mValues.get(position).price));
        holder.mBaseView.setText(String.format(Locale.US, basePrecisionFormatter, mValues.get(position).baseAmount));
        holder.mQuoteView.setText(String.format(Locale.US, AssetUtil.formatAmount(mValues.get(position).price), mValues.get(position).quoteAmount));
        holder.mDateView.setText(mValues.get(position).date);
        if(mValues.get(position).showRed.equals("showRed")) {
            holder.mPriceView.setTextColor(mContext.getResources().getColor(R.color.decreasing_color));
        } else {
            holder.mPriceView.setTextColor(mContext.getResources().getColor(R.color.increasing_color));
        }
    }

    @Override
    public int getItemCount() {
        return mValues.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        MarketTrade mItem;
        final View mView;
        final TextView mPriceView;
        final TextView mQuoteView;
        TextView mBaseView;
        TextView mDateView;

        ViewHolder(View view) {
            super(view);
            mView = view;
            mPriceView = (TextView) view.findViewById(R.id.market_page_trade_history_price_value);
            mQuoteView = (TextView) view.findViewById(R.id.market_page_trade_history_quote_volume);
            mBaseView = (TextView) view.findViewById(R.id.market_page_trade_history_base_volume);
            mDateView = (TextView) view.findViewById(R.id.market_page_trade_history_date_value);
        }
    }
}
