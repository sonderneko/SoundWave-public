var projectPath = window.location.origin + window.location.pathname.substring(0, window.location.pathname.substr(1).indexOf('/') + 1);
/**创建一个焦点图专栏*/
/**
 * c  {JSONObject}  专区分栏信息
 * data  {JSONArray}  对应专区分栏的显示内容
 */
function createBanner(c,data, index){
	var htmlStr = '';
	htmlStr += '<div class="fx">';
	htmlStr += '<div id="pic'+index+'" class="pic">';
	for(var i=0; data&&i<data.length; i++){
		var d = data[i];
		var act = getAction(d.source, d.sourceId);
		if(act.indexOf('javascript')==-1 && act.indexOf('http')>-1){
			act = 'javascript:window.location.href=\''+act+'\'';
		}
		htmlStr += '<img src="'+d.focusPic+'" width="100%" style="display:'+(i?'none':'block')+';" onclick="'+act+'" />';
	}
	htmlStr += '</div>';
	htmlStr += '<div id="but'+index+'" class="but" style="margin-left:-'+(14*(data?data.length:0)/2)+'px;">';
	htmlStr += '<ul>';
	for(var i=0; data&&i<data.length; i++){
		htmlStr += '<li '+(i?'':'class="hov"')+'></li>';
	}
	htmlStr += '</ul>';
	htmlStr += '</div>';
	htmlStr += '</div>';
	return htmlStr;
}

/**创建一个4S专栏*/
/**
 * c  {JSONObject}  专区分栏信息
 * data  {JSONArray}  对应专区分栏的显示内容
 */
function create4s(c,data){
	var htmlStr = '';
	htmlStr += '<div class="clab">';
	htmlStr += '<div class="classifyb">';
	htmlStr += '<ul>';
	for(var i=0; data&&i<data.length; i++){
		var d = data[i];
		htmlStr += '<li> <a href="'+getAction(d.source, d.sourceId)+'"> <img src="'+(d.pic ? d.pic : d.pic1)+'">';
		htmlStr += '<p>'+d.title+'</p>';
		htmlStr += '</a> </li>';
	}
	htmlStr += '</ul>';
	htmlStr += '</div>';
	htmlStr += '</div>';
	return htmlStr;
}

/**创建一个方形卡片专栏*/
/**
 * c  {JSONObject}  专区分栏信息
 * data  {JSONArray}  对应专区分栏的显示内容
 */
function createSquare(c,data){
	var htmlStr = '';
	htmlStr += '<div class="bank">';
	if(c.isuntitled==0){
		htmlStr += '<p class="pone">'+c.title+'</p>';
	}
	htmlStr += '<div class="clax">';
	htmlStr += '<div class="classifyx">';
	htmlStr += '<ul>';
	for(var i=0; data&&i<data.length; i++){
		var d = data[i];
		htmlStr += '<li> <a href="'+getAction(d.source, d.sourceId)+'">';
		htmlStr += '<div class="relative"><img class="square" src="'+(d.pic ? d.pic : d.pic1)+'">';
		if(d.flag==2){//hot
			htmlStr += '<div class="hot"><img class="img" src="'+projectPath+'/fspage/zhuanqu/images/hot.png"></div>';
		}
//		htmlStr += '<div class="ej"><span class="img"></span></div>';   //去掉播放小icon
//		htmlStr += '<div class="ej"><span class="img"></span>'+d.info+'</div>';
		htmlStr += '</div>';
		htmlStr += '<p>'+d.title+'</p>';
		htmlStr += '</a> </li>';
	}
	htmlStr += '</ul>';
	htmlStr += '</div>';
	htmlStr += '</div>';
	htmlStr += '</div>';
	return htmlStr;
}

/**创建一个长方形卡片专栏*/
/**
 * c  {JSONObject}  专区分栏信息
 * data  {JSONArray}  对应专区分栏的显示内容
 */
function createMVsquare(c,data){
	var htmlStr = '';
	htmlStr += '<div class="bank">';
	if(c.isuntitled==0){
		htmlStr += '<p class="pone">'+c.title+'</p>';
	}
	htmlStr += '<div class="clax">';
	htmlStr += '<div class="classifyd">';
	htmlStr += '<ul>';
	for(var i=0; data&&i<data.length; i++){
		var d = data[i];
		htmlStr += '<li> <a href="'+getAction(d.source, d.sourceId)+'">';
		htmlStr += '<div class="relative"><img class="mvsquare" src="'+(d.pic ? d.pic : d.pic1)+'">';
		if(d.flag==2){//hot
			htmlStr += '<div class="hot"><img class="img" src="'+projectPath+'/fspage/zhuanqu/images/hot.png"></div>';
		}
		htmlStr += '</div>';
		htmlStr += '<p>'+d.title+'</p>';
		htmlStr += '</a> </li>';
	}
	htmlStr += '</ul>';
	htmlStr += '</div>';
	htmlStr += '</div>';
	htmlStr += '</div>';
	return htmlStr;
}

/**创建一个列表专栏*/
/**
 * c  {JSONObject}  专区分栏信息
 * data  {JSONArray}  对应专区分栏的显示内容
 */
function createList(c,data){
	var htmlStr = "";
	htmlStr += '<div class="bank">';
	if(c.isuntitled==0){
		htmlStr += '<p class="pone">'+c.title+'</p>';
	}
	htmlStr += '<p class="h5"></p>';
	htmlStr += '<div class="clax">';
	for(var i=0; data&&i<data.length; i++){
		var d = data[i];
		if(i!=0){
			htmlStr += '<p class="clear"></p>';
		}
		htmlStr += '<a href="'+getAction(d.source, d.sourceId)+'">'
		htmlStr += '<div class="kg_top"><img  class="img2" src="'+(d.pic ? d.pic : d.pic1)+'" width="55px">';
		if(d.flag==2){//hot
			htmlStr += '<div class="hot"><img class="img" src="'+projectPath+'/fspage/zhuanqu/images/hot.png"></div>';
		}
		htmlStr += '<div class="p">';
		htmlStr += '<p class="ppone">'+d.title+'</p>';
		htmlStr += '<p class="pptwo">'+d.info+'</p>';
		htmlStr += '</div>';
		htmlStr += '</div>';
		htmlStr += '</a>';
	}
	htmlStr += '</div>';
	htmlStr += '<div>';
	return htmlStr;
}

/**创建一个单曲模块专栏*/
/**
 * c  {JSONObject}  专区分栏信息
 * data  {JSONArray}  对应专区分栏的显示内容
 */
function createMusic(c,data,music){
	var htmlStr = '';
	htmlStr += '<div class="bank">';
	if(c.isuntitled==0){
		htmlStr += '<p class="pone">'+c.title+'</p>';
	}
	htmlStr += '<div class="clax">';
	for(var i=0; data&&i<data.length; i++){
		var d = data[i];
		var act = getAction(d.source, d.sourceId);
		if(act.indexOf('javascript')==-1 && act.indexOf('http')>-1){
			act = 'javascript:window.location.href=\''+act+'\'';
		}
		htmlStr += '<div class="singBox" onclick="'+act+'">';
		htmlStr += '<div class="singTex">';
		htmlStr += '<div class="singTexUp">';
//		htmlStr += '<p class="singTexUp2">'+d.title+'<span class="spanSing"><img src="images/hq.png" width="100%"></span></p>';
		htmlStr += '<p class="singTexUp2">'+d.title+'<span class="spanSing"></span></p>';
		htmlStr += '</div>';
		htmlStr += '<p class="singName">'+d.info+'</p>';
		htmlStr += '</div>';
//		htmlStr += '<a href="#" class="singDom2"><img src="images/csz.png" width="100%"></a>';
		if(music[d.sourceId]&&music[d.sourceId].mvrid!='MV_0'){
//			htmlStr += '<a href="'+music[d.sourceId].mvurl+'" class="singMv"><img src="images/ys.png" width="100%"></a>';
			htmlStr += '<a href="//mobile.kuwo.cn/mv/'+d.sourceId+'" class="singMv"><img src="images/ys.png" width="100%"></a>';
		}
		htmlStr += '</div>';
	}
	htmlStr += '</div>';
	htmlStr += '</div>	';
	return htmlStr;
}

/**焦点图控制函数*/
/**
 * c  {JSONObject}  专区分栏信息
 * data  {JSONArray}  对应专区分栏的显示内容
 */
function controlBanner(){
	var fx = $(".fx");
	setInterval(function(){
		for(var i=0; i<fx.length; i++){
			auto(i);
		}
	},4000);
	var x1,x2;
	$(".fx").bind("touchstart",function(e){
		x1=x2=e.originalEvent.targetTouches[0].pageX;
		var id = e.currentTarget.childNodes[0].id;
		$("#"+id).attr("flag","1");
	});
	$(".fx").bind("touchmove",function(e){
		e.preventDefault();
		x2=e.originalEvent.targetTouches[0].pageX;
	});
	$(".fx").bind("touchend",function(e){
		var id = e.currentTarget.childNodes[0].id;
		$("#"+id).removeAttr("flag");
		if(Math.abs(x1-x2)>50)
			auto(id.replace("pic",""), x1-x2<0);
	});
	function auto(index, orientation){  //true:向左       false:向右
		if($("#pic"+index).attr("flag"))return;
		var a = $("#but"+index).attr("num");
		a || (a=0);
		a++;
		if(orientation){
			if(a-2<0)a=$("#pic"+index+" img").length+1;
			a=a-2;
		}
		if(a<$("#but"+index+" ul li").length){
			if(a == $("#but"+index+" ul li").length-1){a=-1;};
			$("#but"+index+" ul li").eq(a).addClass("hov").siblings().removeClass("hov");
			$("#pic"+index+" img").eq(a).fadeIn().siblings().hide();
		}else{
			a = -1;
		}
		$("#but"+index).attr("num", a);
	}
};

function getAction(source, sourceid){
	var act = '';
	switch(eval(source)){
		case 2  : {
			act = '//m.kuwo.cn/?onefl='+sourceid;
		}break;    // 排行榜榜单
		case 4  : {
			act = '//m.kuwo.cn/?artistid='+sourceid+'&bdfrom=area';
		}break;    // 歌手
		case 8  :  // 播放列表
		case 43 :  // 2015专区ID——咱们支持后就有了
		case 14 : {
			act = '//m.kuwo.cn/?pid='+sourceid+'&bdfrom=area';
		}break;    // MV列表
		case 7  : {
			act = '//mobile.kuwo.cn/mv/'+sourceid;
		}break;    // MV
		case 13 : {
			act = '//m.kuwo.cn/?albumid='+sourceid+'&bdfrom=area';
		}break;    // 专辑
		case 15 : {
			act = '//m.kuwo.cn/?mid=MUSIC_'+sourceid+'&bdfrom=area';
		}break;    // 单曲
		default : {
			act = "javascript:popShow()";
		}
	}
	return act;
}
function popClose(){
	$(".kg_dbt").hide();
	$(".hy_tc").hide();
}
function popShow(){
	$(".kg_dbt").show();
	$(".hy_tc").show();
}


function downapk(param){
	if(param=='pop'){popClose();}
	var downbaourl="";
	if(browser.versions.isWeiXin){
//		showTip();
		window.location.href="//a.app.qq.com/o/simple.jsp?pkgname=cn.kuwo.player&g_f=997556";
		return;
	}
	if(browser.plat.ios){
		if(browser.versions.safari){
			window.location.href="https://itunes.apple.com/cn/app/ku-wo-yin-le-qiang-xian-ban/id588673713?mt=8";
			sendLog("download");
			return;
		}
		downbaourl="https://itunes.apple.com/cn/app/ku-wo-yin-le-qiang-xian-ban/id588673713?mt=8";
	}else{
		downbaourl="//down.shouji.kuwo.cn/star/mobile/kwplayer_ar_kwmusic_m6.apk";
	}
	sendLog("download");
	$('body').append('<iframe src="'+downbaourl+'" style="display:none;"></iframe>');
}

function showTip(){
	var htmlstr = '';
	//<!--弹窗图片-->
	htmlstr += '<img tip="tip" class="tk1_img" src="images/mb.png" width="100%" onclick="removeTop()">';
	if(document.getElementById("tip")){
		return;
	}
	$('body').prepend(htmlstr);
}
function removeTop(){
	$('p[tip="tip"]').remove();
	$('img[tip="tip"]').remove();
}

function sendLog(_actiontype){
	window.setTimeout(function(){
//		new Image().src="//221.238.18.38:8014/mlog.s?logtype=weblog&zhuanqutype="+dataId+"&actiontype="+_actiontype;
		new Image().src="//logmonitor.kuwo.cn/mlog.s?logtype=weblog&zhuanqutype="+dataId+"&actiontype="+_actiontype;
		               ////logmonitor.kuwo.cn/mlog.s?logtype=weblog&zhuanqutype=6&actiontype=download
	},100);
}