package com.cybexmobile.adapter;

import android.content.Context;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.cybexmobile.fragment.data.WatchListData;
import com.cybexmobile.fragment.WatchLIstFragment.OnListFragmentInteractionListener;
import com.cybexmobile.R;
import com.cybexmobile.utils.MyUtils;
import com.squareup.picasso.Picasso;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;

public class WatchListRecyclerViewAdapter extends RecyclerView.Adapter<WatchListRecyclerViewAdapter.ViewHolder> {

    private final List<WatchListData> mValues;
    private final OnListFragmentInteractionListener mListener;
    private Context mContext;

    public WatchListRecyclerViewAdapter(List<WatchListData> items, OnListFragmentInteractionListener listener, Context context) {
        mValues = items;
        mListener = listener;
        mContext = context;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                 .inflate(R.layout.watch_list_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, final int position) {
        holder.mItem = mValues.get(position);
        NumberFormat formatter = MyUtils.getSuitableDecimalFormat(holder.mItem != null ? holder.mItem.getQuote() : "");
        NumberFormat formatter2 = new DecimalFormat("0.00");
        holder.mItem = mValues.get(position);
        if (mValues.get(position).getBase().contains("JADE")) {
            holder.mBaseCurrency.setText(mValues.get(position).getBase().substring(5, mValues.get(position).getBase().length()));
        } else {
            holder.mBaseCurrency.setText(mValues.get(position).getBase());
        }
        if (mValues.get(position).getQuote().contains("JADE")) {
            holder.mQuoteCurrency.setText(String.format("/%s", mValues.get(position).getQuote().substring(5, mValues.get(position).getQuote().length())));
        } else {
            holder.mQuoteCurrency.setText(String.format("/%s", mValues.get(position).getQuote()));
        }
        holder.mVolume.setText(holder.mItem.getVol() == 0.f ? "-" : String.format("Volume:%s", MyUtils.getNumberKMGExpressionFormat(mValues.get(position).getVol())));
        holder.mCurrentPrice.setText(holder.mItem.getCurrentPrice() == 0.f ? "-" : String.valueOf(formatter.format(mValues.get(position).getCurrentPrice())));

        double change = 0.f;
        if(mValues.get(position).getChange()!= null) {
            try {
                change = Double.parseDouble(mValues.get(position).getChange());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if( change > 0.f) {
            holder.mChangeRate.setText(String.format("+%s%%",String.valueOf(formatter2.format(change * 100))));
            holder.mChangeRate.setBackgroundColor(mContext.getResources().getColor(R.color.increasing_color));

        } else if (change < 0.f) {
            holder.mChangeRate.setText(String.format("%s%%",String.valueOf(formatter2.format(change * 100))));
            holder.mChangeRate.setBackgroundColor(mContext.getResources().getColor(R.color.decreasing_color));

        } else {
            holder.mChangeRate.setText("0.00%");
            holder.mChangeRate.setBackgroundColor(mContext.getResources().getColor(R.color.no_change_color));
        }

        holder.mView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (null != mListener) {
                    // Notify the active callbacks interface (the activity, if the
                    // fragment is attached to one) that an item has been selected.
                    mListener.onListFragmentInteraction(holder.mItem, mValues, position);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return mValues.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        public final View mView;
        public WatchListData mItem;
        TextView mBaseCurrency;
        TextView mQuoteCurrency;
        TextView mCurrentPrice;
        TextView mVolume;
        TextView mChangeRate;

        public ViewHolder(View view) {
            super(view);
            mView = view;
            mBaseCurrency = (TextView) view.findViewById(R.id.base_currency_watchlist);
            mQuoteCurrency = (TextView) view.findViewById(R.id.quote_currency_watchlist);
            mCurrentPrice = (TextView) view.findViewById(R.id.current_price_watchlist);
            mVolume = (TextView) view.findViewById(R.id.volume);
            mChangeRate = (TextView) view.findViewById(R.id.change_rate_watchlist);
        }

        @Override
        public String toString() {
            return super.toString() + " '" + "'";
        }
    }

    private void loadImage(String quoteId, ImageView mCoinSymbol) {
        String quoteIdWithUnderLine = quoteId.replaceAll("\\.", "_");
        Picasso.get().load("https://cybex.io/icons/" + quoteIdWithUnderLine +"_grey.png").into(mCoinSymbol);
    }

    public void setItemToPosition(WatchListData watchListData, int position) {
        mValues.set(position, watchListData);
        notifyItemChanged(position);
    }
}
