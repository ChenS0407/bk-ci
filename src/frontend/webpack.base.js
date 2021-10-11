const webpack = require('webpack')
const path = require('path')
const MiniCssExtractPlugin = require('mini-css-extract-plugin')
const { VueLoaderPlugin } = require('vue-loader')
const CopyWebpackPlugin = require('copy-webpack-plugin')
const BundleWebpackPlugin = require('./webpackPlugin/bundle-webpack-plugin')
// const BundleAnalyzerPlugin = require('webpack-bundle-analyzer').BundleAnalyzerPlugin

module.exports = ({ entry, publicPath, dist, port = 8080, argv, env }) => {
    const isDev = argv.mode === 'development'
    const envDist = env && env.dist ? env.dist : 'frontend'
    const version = env && env.version ? env.version : 'tencent'
    const buildDist = path.join(__dirname, envDist, dist)
    console.log(path.join(__dirname, 'locale', dist), version)
    return {
        cache: true,
        devtool: 'source-map',
        entry,
        output: {
            publicPath: isDev ? `http://dev.static.devops.oa.com${publicPath}` : publicPath,
            chunkFilename: '[name].[chunkhash].js',
            filename: '[name].[contenthash].min.js',
            path: buildDist,
            assetModuleFilename: '[name].[ext]?[contenthash]'
        },
        module: {
            rules: [
                {
                    test: /\.vue$/,
                    include: [path.resolve('src'), path.resolve('../node_modules/vue-echarts')],
                    loader: 'vue-loader'
                },
                {
                    test: /\.js$/,
                    include: [path.resolve('src'), path.resolve('../node_modules/vue-echarts')],
                    use: [
                        {
                            loader: 'babel-loader'
                        }
                    ]
                },
                {
                    test: /\.css$/,
                    use: [MiniCssExtractPlugin.loader, 'css-loader']
                },
                {
                    test: /\.scss$/,
                    use: [MiniCssExtractPlugin.loader, 'css-loader', 'sass-loader']
                },
                {
                    test: /\.(js|vue)$/,
                    loader: 'eslint-loader',
                    enforce: 'pre',
                    include: [path.resolve('src')],
                    exclude: /node_modules/,
                    options: {
                        fix: true,
                        formatter: require('eslint-friendly-formatter')
                    }
                },
                {
                    test: /\.cur$/,
                    type: 'asset/resource'
                },
                {
                    test: /\.(png|jpe?g|gif|svg|webp|woff2?|eot|ttf|otf)(\?.*)?$/,
                    type: 'asset',
                    parser: {
                        dataUrlCondition: {
                            maxSize: 8 * 1024
                        }
                    }
                }
                
            ]
        },
        plugins: [
            // new BundleAnalyzerPlugin(),
            new VueLoaderPlugin(),
            new BundleWebpackPlugin({
                dist: envDist,
                bundleName: 'assets_bundle'
            }),
            new MiniCssExtractPlugin({
                filename: '[name].[contenthash].css',
                chunkFilename: '[id].css',
                ignoreOrder: true
            }),
            new webpack.DefinePlugin({
                VERSION_TYPE: JSON.stringify(version)
            }),
            new CopyWebpackPlugin([{ from: path.join(__dirname, 'locale', dist), to: buildDist }])
        ],
        optimization: {
            chunkIds: 'named',
            moduleIds: 'deterministic',
            minimize: !isDev
        },
        resolve: {
            extensions: ['.js', '.vue', '.json', '.ts', '.scss', '.css'],
            fallback: {
                'path': false
            },
            alias: {
                '@': path.resolve('src'),
                '@locale': path.resolve(__dirname, 'locale')
            }
        },
        externals: {
            'vue': 'Vue',
            'vue-router': 'VueRouter',
            'vuex': 'Vuex'
        },
        devServer: {
            contentBase: path.join(__dirname, envDist),
            historyApiFallback: true,
            noInfo: false,
            disableHostCheck: true,
            port
        }
    }
}
